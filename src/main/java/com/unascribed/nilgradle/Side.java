package com.unascribed.nilgradle;

public enum Side {

    CLIENT,
    SERVER,
    JOINED;

    public <T> T pickObject(T joined, T client, T server) {
        if (this == JOINED && joined != null) return joined;
        if (this == CLIENT && client != null) return client;
        if (this == SERVER && server != null) return server;
        // Fallback if preferred object is not available or no side is explicitly preferred
        if (joined != null) return joined;
        if (client != null) return client;
        if (server != null) return server;
        return null; // Should never happen, but just in case some side was added to the enum
    }

    public static Side parse(Object obj) {
        if (obj == null) {
            return null;
        } else if (obj instanceof Side) {
            return (Side) obj;
        } else if (obj instanceof Integer) {
            return Side.values()[(Integer) obj];
        } else {
            return Side.valueOf(obj.toString().toUpperCase());
        }
    }

}
