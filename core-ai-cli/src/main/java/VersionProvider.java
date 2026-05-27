import ai.core.cli.upgrade.VersionUtil;
import picocli.CommandLine;

/**
 * @author stephen
 */
public class VersionProvider implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() {
        return new String[]{VersionUtil.getCurrentVersion()};
    }
}
