package ai.core.cli.ui;

import java.io.FilterWriter;
import java.io.IOException;
import java.io.Writer;

/**
 * @author stephen
 */
final class CrLfFilterWriter extends FilterWriter {
    CrLfFilterWriter(Writer out) {
        super(out);
    }

    @Override
    public void write(int c) throws IOException {
        if (c == '\n') out.write('\r');
        out.write(c);
    }

    @Override
    public void write(char[] cbuf, int off, int len) throws IOException {
        for (int i = off; i < off + len; i++) {
            write(cbuf[i]);
        }
    }

    @Override
    public void write(String str, int off, int len) throws IOException {
        for (int i = off; i < off + len; i++) {
            write(str.charAt(i));
        }
    }
}
