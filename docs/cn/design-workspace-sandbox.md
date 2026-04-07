# 📄 Architecture Design: Agent Sandbox & Secure Workspace (AKS Edition)

---

## 1. 核心愿景 (Vision)

构建一套高性能、强隔离的执行环境，使 Server 端 Agent 能够像在本地一样安全地分析代码、执行工具（Python/Shell/Browser），同时确保公司内部 Git 凭据不泄露，且对现有的 `ToolExecutor` 体系侵入最小。

---

## 2. 核心架构 (The High-Level Architecture)



### 2.1 组件说明 (Component Breakdown)
* **Sandbox Manager (Provider)**: 驻留在 Java 应用中的控制平面。负责与 AKS API 交互，管理 Pod 的生命周期、预热池 (Warm Pool) 及 Session 绑定。
* **Sandbox Runtime (Pod Internal)**: 运行在沙箱 Pod 内的轻量级服务（Go/Python），负责接收并执行来自 `ToolExecutor` 的指令。
* **Git Sync Service**: 独立特权服务，负责管理 Git 凭据（Deploy Keys），并将代码克隆到共享存储卷。
* **Shared Storage (Azure Files/NFS)**: 提供 **Read-Write-Many (RWX)** 能力，支持代码在同步服务与多个沙箱 Pod 之间共享。

---

## 3. Sandbox 抽象与拦截设计

### 3.1 零侵入拦截逻辑 (Interceptor Pattern)
利用你之前的草稿设计，在 `ToolExecutor` 层面进行拦截，确保 CLI 环境（本地）和 Server 环境（AKS）逻辑一致。

```java
// ToolExecutor.java
private ToolCallResult doExecute(FunctionCall functionCall, ExecutionContext context) {
    var tool = findTool(functionCall);
    var sandbox = context.getSandbox();

    // 如果当前环境注入了 Sandbox，且工具属于拦截范围（如 read_file, run_bash）
    if (sandbox != null && sandbox.shouldIntercept(tool.getName())) {
        // 转发给远程 AKS Pod 执行
        return sandbox.execute(tool.getName(), functionCall.function.arguments);
    }

    return executeWithTimeout(tool, functionCall, context);
}
```

### 3.2 Sandbox 接口实现 (AKSSandbox)
不再直接操作本地文件系统，而是通过 RPC/HTTP 转发指令。

```java
public class AKSSandbox implements Sandbox {
    private final String podIp;       // 目标沙箱 Pod 的内部 IP
    private final String workspaceId; // 绑定的代码库 ID
    private final SandboxClient client;

    @Override
    public ToolCallResult execute(String toolName, String arguments) {
        // 协议封装：将参数发送给 Pod 内部的 Runtime 接口
        // Endpoint: http://{podIp}:8080/execute
        return client.callRemote(podIp, toolName, arguments);
    }
}
```

---

## 4. Workspace & Git 集成细节

### 4.1 存储与挂载策略 (Security by Mounting)


* **物理隔离**：所有的 Git Repo 存储在统一的 PVC 中。
* **逻辑隔离**：通过 K8s 的 `subPath` 特性，每个 Sandbox Pod 启动时只挂载其对应的 Repo 路径。
* **强制只读 (v1)**：Pod 挂载配置中显式声明 `readOnly: true`。

### 4.2 Git Sync 生命周期
1.  **Create/Refresh**: `GitSyncService` 接收请求，使用 Secret 中的 SSH Key 拉取代码到 `/mnt/storage/repos/{workspaceId}`。
2.  **Mount**: `SandboxProvider` 获取该路径，生成 Pod 定义并启动。
3.  **No Credential**: 沙箱 Pod 环境变量和文件系统中均不包含 Git 凭据，Agent 无法通过 Git 命令向外推送代码。

---

## 5. 性能优化：预热池 (Warm Pool)

由于 AKS 创建 Pod 涉及镜像拉取和节点调度（耗时 5s-30s），必须引入预热机制：

* **Standby 池**：始终维持 $N$ 个处于 `Ready` 状态的空闲 Pod。
* **快速绑定**：当 `acquire` 请求到来时，Manager 仅需更新 Pod 的标签（Label）或通过 API 通知 Pod 内部 Runtime 切换 Workspace 路径。
* **冷启动回退**：若并发极高导致池空，则同步创建新 Pod，并向 Agent 返回“环境准备中”的状态。

---

## 6. 安全边界设计 (Security Guardrails)

### 6.1 运行级隔离 (RuntimeClass)
在 AKS 上配置 `RuntimeClass`：
* 使用 **Kata Containers**：为每个沙箱提供独立内核，防止容器逃逸。
* 使用 **gVisor**：拦截系统调用，提供轻量级沙箱化。

### 6.2 网络防御 (NetworkPolicy)
```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
spec:
  podSelector:
    matchLabels:
      component: sandbox
  egress:
  - to:
    - ipBlock:
        cidr: 0.0.0.0/0
        except:
        - 10.0.0.0/8      # 阻断所有公司内网访问
        - 169.254.169.254/32 # 阻断云平台元数据访问
```

---

## 7. 详细 API 定义

| 接口 | 方法 | 功能 |
| :--- | :--- | :--- |
| `/api/workspaces` | `POST` | 创建工作空间并异步克隆 Git Repo |
| `/api/workspaces/{id}/sync` | `PUT` | 强制拉取 Git 最新代码 (Reset Hard) |
| `/api/sandbox/allocate` | `POST` | 从池中申请一个绑定了特定 Workspace 的沙箱 |
| `/api/sandbox/{id}/execute` | `POST` | (内部) 向沙箱发送执行指令 |

---

## 8. 演进阶段 (Phased Implementation)

### 阶段 1 (MVP)
* 实现 `LocalSandbox` 的路径校验增强版。
* 完成 `GitSyncService` 的基础克隆逻辑。
* 通过 `subPath` 挂载 PVC 到 Pod。

### 阶段 2 (性能与安全)
* 落地 **Warm Pool**，实现秒级沙箱分配。
* 配置 AKS 的 **Kata Containers** 节点池。
* 实现 **Egress NetworkPolicy** 封锁。

### 阶段 3 (全功能)
* 支持 **Read-Write** 模式（基于 Git Worktree）。
* 集成 **Playwright** 提供浏览器沙箱能力。
* 添加执行日志审计与 **DLP (数据防泄漏)** 扫描。

---

### 运维建议 (Ops Note)
* **存储选择**：在 AKS 上优先选择 **Azure Files (Premium)** 以获得低延迟的 I/O 表现。
* **资源监控**：重点监控沙箱 Pod 的 `OOMKill` 事件，动态调整 Agent 可用的内存上限。