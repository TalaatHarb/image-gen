package net.talaatharb.imagegen;

/** Plain main class so the fat jar can start JavaFX from the classpath. */
public final class ImageGenLauncher {
    private ImageGenLauncher() {
    }

    public static void main(String[] args) {
        ImageGenApp.main(args);
    }
}
