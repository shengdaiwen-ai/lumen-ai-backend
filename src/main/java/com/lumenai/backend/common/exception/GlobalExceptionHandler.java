package com.lumenai.backend.common.exception;

import com.lumenai.backend.common.response.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public R<Void> handleBusinessException(BusinessException ex) {
        log.warn("Business error: code={}, errorCode={}, msg={}", ex.getCode(), ex.getErrorCode(), ex.getMessage());
        return R.fail(ex.getCode(), ex.getErrorCode(), ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValidation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return R.fail(400, msg);
    }

    @ExceptionHandler(BindException.class)
    public R<Void> handleBind(BindException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        return R.fail(400, msg);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public R<Void> handleNotFound(NoResourceFoundException ex) {
        return R.fail(404, "Resource not found: " + ex.getResourcePath());
    }

    @ExceptionHandler(Exception.class)
    public R<Void> handleUnknown(Exception ex) {
        log.error("Unexpected error", ex);
        return R.fail(500, "Internal server error: " + ex.getMessage());
    }
}
