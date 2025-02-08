package ai.core.defaultagents;

import ai.core.agent.ImageAgent;
import ai.core.ImageProvider;

/**
 * @author stephen
 */
public class DefaultImageGenerateAgent {
    public static ImageAgent of(ImageProvider imageProvider) {
        return ImageAgent.builder().prompt("""
                                           Base on my query draw high quality images and adhere to the following rules:
                                           1. no bad hand
                                           2. no deformity
                                           3. no extra fingers
                                           4. no disfigured
                                           5. no bad anatomy
                                           6. no extra limbs
                                           7. no error legs
                                           8. no bad feet
                                           9. no poorly drawn face or cloned face
                                           10. do not draw 3+ persons in the image
                                           11. do not draw collage image
                                           12. image style is authentic street photography
                                           query:
                                           """).imageProvider(imageProvider).build();
    }
}
