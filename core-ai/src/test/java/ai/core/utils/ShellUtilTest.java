package ai.core.utils;

import org.junit.jupiter.api.Test;

/**
 * @author stephen
 */
class ShellUtilTest {
    @Test
    void test() {
        var rst = ShellUtil.isCommandExists(ShellUtil.getSystemType(), "ripgrep.exe");
        System.out.println(rst);
    }
}