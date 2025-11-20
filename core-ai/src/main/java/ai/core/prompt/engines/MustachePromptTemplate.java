package ai.core.prompt.engines;

import ai.core.prompt.PromptTemplate;
import com.github.mustachejava.DefaultMustacheFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * @author stephen
 */
public class MustachePromptTemplate implements PromptTemplate {
    public static String compile(String template, Map<String, Object> scopes) {
        return new MustachePromptTemplate().execute(template, scopes, UUID.randomUUID().toString());
    }

    @Override
    public String execute(String template, Map<String, Object> scopes, String name) {
        if (scopes == null) return template;
        var output = new ByteArrayOutputStream();
        var writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
        var m = new DefaultMustacheFactory().compile(new StringReader(template), name);
        m.execute(writer, scopes);
        try {
            writer.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return decodeHtmlEntities(output.toString(StandardCharsets.UTF_8));
    }


    private String decodeHtmlEntities(String text) {
        return text.replace("&#96;", "`")
                .replace("&#10;", "\n")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }
}
