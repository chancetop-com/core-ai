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
        // Fast path: scan for \n before falling back to char-by-char
        int end = off + len;
        int pos = off;
        while (pos < end) {
            if (cbuf[pos] == '\n') {
                // Found \n, use char-by-char for the remainder
                for (int i = off; i < end; i++) {
                    write(cbuf[i]);
                }
                return;
            }
            pos++;
        }
        out.write(cbuf, off, len);
    }

    @Override
    public void write(String str, int off, int len) throws IOException {
        // Fast path: no \n in segment, pass through atomically to avoid visual flicker on Windows
        int nlIdx = str.indexOf('\n', off);
        if (nlIdx < 0 || nlIdx >= off + len) {
            out.write(str, off, len);
            return;
        }
        for (int i = off; i < off + len; i++) {
            write(str.charAt(i));
        }
    }
}
