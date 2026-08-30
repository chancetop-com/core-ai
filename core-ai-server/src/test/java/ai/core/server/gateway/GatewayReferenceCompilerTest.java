package ai.core.server.gateway;

import ai.core.media.domain.MediaReference;
import ai.core.media.reference.MediaModality;
import ai.core.media.reference.MediaReferenceRole;
import ai.core.server.domain.GatewayModelConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The token/asset binding is positional, so the prompt tokens and the reference array must be
 * compiled from one ordered list — disagreement puts the wrong face on the wrong body with no error.
 *
 * @author Stephen
 */
class GatewayReferenceCompilerTest {
    private static MediaReference ref(String name, MediaReferenceRole role, MediaModality modality) {
        return new MediaReference(null, null, "gateway-media-v1.img." + name, name, role, modality);
    }

    private static GatewayModelConfig caps(Integer maxImages, String syntax) {
        var model = new GatewayModelConfig();
        model.maxImageReferences = maxImages;
        model.addressingSyntax = syntax;
        return model;
    }

    private final GatewayReferenceCompiler compiler = new GatewayReferenceCompiler();

    @Test
    void rendersNamesInTheTargetModelSyntaxAndOrdersTheArrayToMatch() {
        var references = List.of(
                ref("char_lin", MediaReferenceRole.SUBJECT, MediaModality.IMAGE),
                ref("scene_cafe", MediaReferenceRole.SCENE, MediaModality.IMAGE));

        var compiled = compiler.compile("参考 @char_lin 的角色，放进 @scene_cafe 的场景", references,
                MediaModality.IMAGE, "bytedance/seedance-2-5", null);

        assertEquals("参考 @Image1 的角色，放进 @Image2 的场景", compiled.prompt());
        assertEquals(List.of("char_lin", "scene_cafe"), compiled.references().stream().map(MediaReference::name).toList());
    }

    @Test
    void usesTheFamilySyntaxRegistryNotTheProvider() {
        var references = List.of(ref("char_lin", MediaReferenceRole.SUBJECT, MediaModality.IMAGE));

        // Seedance and MiniMax both arrive via KIE yet address references differently
        assertEquals("<Picture 1> smiles", compiler.compile("@char_lin smiles", references,
                MediaModality.IMAGE, "minimax-h3/reference-to-video", null).prompt());
        assertEquals("@Image1 smiles", compiler.compile("@char_lin smiles", references,
                MediaModality.IMAGE, "bytedance/seedance-2-5", null).prompt());
    }

    @Test
    void stripsTokensForModelsWithNoAddressingSyntax() {
        var references = List.of(ref("char_lin", MediaReferenceRole.SUBJECT, MediaModality.IMAGE));

        // a stray @char_lin in a Seedream prompt is prompt pollution, so it must be rewritten away
        var compiled = compiler.compile("keep @char_lin exactly", references, MediaModality.IMAGE, "seedream/5-pro", null);

        assertEquals("keep reference image 1 exactly", compiled.prompt());
    }

    @Test
    void trimsByRolePriorityRenumbersAndReportsTheDrop() {
        var references = List.of(
                ref("style_ref", MediaReferenceRole.STYLE, MediaModality.IMAGE),
                ref("char_lin", MediaReferenceRole.SUBJECT, MediaModality.IMAGE),
                ref("scene_cafe", MediaReferenceRole.SCENE, MediaModality.IMAGE));

        var compiled = compiler.compile("@char_lin in @scene_cafe with @style_ref", references,
                MediaModality.IMAGE, "unknown-model", caps(2, "AT_TOKEN"));

        assertEquals(List.of("char_lin", "scene_cafe"), compiled.references().stream().map(MediaReference::name).toList());
        // the dropped mention is removed rather than left pointing at an asset that is no longer sent
        assertEquals("@Image1 in @Image2 with ", compiled.prompt());
        assertTrue(compiled.notes().stream().anyMatch(note -> note.contains("style_ref")), compiled.notes().toString());
    }

    @Test
    void reportsReferencesThePromptNeverExplains() {
        var references = List.of(
                ref("char_lin", MediaReferenceRole.SUBJECT, MediaModality.IMAGE),
                ref("scene_cafe", MediaReferenceRole.SCENE, MediaModality.IMAGE));

        var compiled = compiler.compile("@char_lin smiles", references, MediaModality.IMAGE, "bytedance/seedance-2-5", null);

        assertTrue(compiled.notes().stream().anyMatch(note -> note.contains("scene_cafe") && note.contains("not mentioned")),
                compiled.notes().toString());
    }

    @Test
    void positionIsTheNameWhenTheCallerDeclaredNone() {
        var references = List.of(
                new MediaReference(null, null, "gateway-media-v1.img.a", null, null, MediaModality.IMAGE),
                new MediaReference(null, null, "gateway-media-v1.img.b", null, null, MediaModality.IMAGE));

        var compiled = compiler.compile("@image1 and @image2", references, MediaModality.IMAGE, "bytedance/seedance-2-5", null);

        assertEquals("@Image1 and @Image2", compiled.prompt());
    }
}
