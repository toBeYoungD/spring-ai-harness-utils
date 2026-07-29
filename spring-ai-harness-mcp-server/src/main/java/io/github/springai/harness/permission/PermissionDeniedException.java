package io.github.springai.harness.permission;

/**
 * 权限拒绝时抛出的运行时异常。
 * 携带路径和操作信息，便于 GlobalRestExceptionHandler 返回友好错误。
 */
public class PermissionDeniedException extends RuntimeException {

    private final String path;
    private final String operation;
    private final String reason;

    public PermissionDeniedException(String path, String operation, String reason) {
        super(String.format("permission denied: %s %s (%s)", operation, path, reason));
        this.path = path;
        this.operation = operation;
        this.reason = reason;
    }

    public String getPath() {
        return path;
    }

    public String getOperation() {
        return operation;
    }

    public String getReason() {
        return reason;
    }
}
