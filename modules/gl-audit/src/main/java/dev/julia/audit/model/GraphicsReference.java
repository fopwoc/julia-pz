package dev.julia.audit.model;

public record GraphicsReference(GraphicsSymbol target, String callerClass, String callerMethod,
                                String callerDescriptor, Kind kind) {
    public enum Kind { INSTRUCTION, METHOD_HANDLE }
}
