package ai.core.benchmark.domain;

import java.util.List;

/**
 * author: lim chen
 * date: 2025/12/26
 * description:
 */
public class BFCLFileInfo {
    public static BFCLFileInfo of(String name, String category, String path, List<BFCLItem> items) {
        var info = new BFCLFileInfo();
        info.name = name;
        info.path = path;
        info.items = items;
        info.category = category;
        return info;
    }

    public String name;
    public String path;
    public String category;
    public List<BFCLItem> items;

}
