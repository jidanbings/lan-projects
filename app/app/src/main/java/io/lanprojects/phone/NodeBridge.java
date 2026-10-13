package io.lanprojects.phone;

/**
 * JNI bridge into the bundled Node.js runtime (libnode.so + node_bridge.so).
 */
public final class NodeBridge {

    static {
        // Order matters: node_bridge links against libnode.
        System.loadLibrary("node");
        System.loadLibrary("node_bridge");
    }

    private NodeBridge() {
    }

    /** Set an environment variable before starting Node. */
    public static native void setEnv(String name, String value);

    /**
     * Start Node in a detached native thread with the given argv
     * (e.g. {"node", "/data/.../nodejs-project/server/index.js"}).
     * Blocks forever on the native thread; returns immediately to Java.
     */
    public static native void startNodeWithArguments(String[] arguments);
}
