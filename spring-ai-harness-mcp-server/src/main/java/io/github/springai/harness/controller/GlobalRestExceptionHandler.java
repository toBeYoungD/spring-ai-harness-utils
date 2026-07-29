package io.github.springai.harness.controller;

import io.github.springai.harness.auth.AuthenticationException;
import io.github.springai.harness.permission.PermissionDeniedException;
import io.github.springai.harness.storage.QuotaExceededException;
import io.github.springai.harness.storage.QuotaExceededException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/**
 * 全局 REST 异常处理器。
 *
 * @author ichaobuster
 */
@RestControllerAdvice
@Slf4j
public class GlobalRestExceptionHandler {

	/**
	 * 处理容量超限相关的异常，返回 413 Payload Too Large。
	 */
	@ExceptionHandler(QuotaExceededException.class)
	public ResponseEntity<Map<String, String>> handleQuotaExceededException(HttpServletRequest request, QuotaExceededException e) {
		log.error("工作空间容量超限 [{} {}]: {}", request.getMethod(), request.getRequestURI(), e.getMessage(), e);
		return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
				.body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Workspace quota exceeded"));
	}

	/**
	 * 处理认证相关的异常，返回 401 Unauthorized。
	 */
	@ExceptionHandler(AuthenticationException.class)
	public ResponseEntity<Map<String, String>> handleAuthenticationException(HttpServletRequest request, AuthenticationException e) {
		log.warn("认证失败 [{} {}]: {}", request.getMethod(), request.getRequestURI(), e.getMessage(), e);
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
				.body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Unauthorized"));
	}

	@ExceptionHandler(PermissionDeniedException.class)
	public ResponseEntity<Map<String, String>> handlePermissionDeniedException(HttpServletRequest request, PermissionDeniedException e) {
		log.warn("权限拒绝 [{} {}]: path={} op={}", request.getMethod(), request.getRequestURI(), e.getPath(), e.getOperation());
		return ResponseEntity.status(HttpStatus.FORBIDDEN)
				.body(Map.of("error", "permission denied",
						"path", e.getPath() != null ? e.getPath() : "",
						"operation", e.getOperation() != null ? e.getOperation() : "",
						"reason", e.getReason() != null ? e.getReason() : ""));
	}

	/**
	 * 处理安全相关的异常（如绝对路径访问限制），返回 400 Bad Request。
	 */
	@ExceptionHandler(SecurityException.class)
	public ResponseEntity<Map<String, String>> handleSecurityException(HttpServletRequest request, SecurityException e) {
		log.error("安全检查失败 [{} {}]: {}", request.getMethod(), request.getRequestURI(), e.getMessage(), e);
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Security violation"));
	}

	/**
	 * 处理非法参数异常，返回 400 Bad Request。
	 */
	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, String>> handleIllegalArgumentException(HttpServletRequest request, IllegalArgumentException e) {
		log.error("参数校验失败 [{} {}]: {}", request.getMethod(), request.getRequestURI(), e.getMessage(), e);
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
				.body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Invalid argument"));
	}

	/**
	 * 处理其他所有未捕获的异常，返回 500 Internal Server Error。
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<Map<String, String>> handleGenericException(HttpServletRequest request, Exception e) {
		log.error("服务器内部错误 [{} {}]: {}", request.getMethod(), request.getRequestURI(), e.getMessage(), e);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(Map.of("error", e.getMessage() != null ? e.getMessage() : "Internal server error"));
	}
}
