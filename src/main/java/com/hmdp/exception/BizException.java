package com.hmdp.exception;

import com.hmdp.dto.ErrorCode;
public class BizException extends RuntimeException {

    private  final ErrorCode errorCode;
    public BizException(ErrorCode ec) {
        this(ec,null);
    }
    public BizException(ErrorCode ec, String message) {
        super(message!=null?message:ec.getMsg());
        this.errorCode = ec;
    }
    public BizException(String message) {
        this(ErrorCode.BIZ_GENERAL,message);
    }
    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
