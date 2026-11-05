package net.talaatharb.imagegen.service;

import java.io.IOException;

import net.talaatharb.imagegen.model.ProgressListener;

/**
 * Common lifecycle of a locally executed image model: install (download) once, load lazily, release on exit. Every
 * image task (generation today; editing, upscaling, ... later) builds its service interface on top of this one.
 */
public interface ImageModelService extends AutoCloseable {
    /** Stable identifier, e.g. {@code flux1-schnell}. */
    String id();

    String displayName();

    /** One-line description of the model, its download size and license. */
    String description();

    /** True when every file needed to run the model is in the local cache. */
    boolean isInstalled();

    /** Downloads any missing model files. Safe to call repeatedly. */
    void install(ProgressListener progress) throws IOException;

    /** Approximate number of bytes {@link #install} still has to download (0 when installed). */
    default long missingDownloadBytes() {
        return 0;
    }

    @Override
    default void close() {
    }
}
