package org.fraudDetection.rust;

import org.graalvm.nativeimage.c.CContext;
import java.util.Collections;
import java.util.List;

/** Directives da C-interface: header + lib estática linkados no native-image. */
public final class FdDirectives implements CContext.Directives {
    @Override public List<String> getHeaderFiles() {
        return Collections.singletonList("\"fdsearch.h\"");
    }
    @Override public List<String> getLibraries() {
        return Collections.singletonList("fdsearch"); // => libfdsearch.a via CLibraryPath
    }
}
