package org.fabricioyv.queue;

public class QueueResult {
    private final boolean success;
    private final String message;

    private QueueResult(boolean success, String message) {
        this.success = success;
        this.message = message;
    }

    public static QueueResult success(String message) {
        return new QueueResult(true, message);
    }

    public static QueueResult failure(String message) {
        return new QueueResult(false, message);
    }

    // Helpers opcionales (si quieres usarlos luego)
    public static QueueResult ok(String message) { return success(message); }
    public static QueueResult fail(String message) { return failure(message); }

    public boolean isSuccess() { return success; }
    public String getMessage() { return message; }
}