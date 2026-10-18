package com.pachy.highlight.service.clip;

public class ClipSourceException extends RuntimeException {
    private final int status;
    private final String code;
    public ClipSourceException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
    public int status() { return status; }
    public String code() { return code; }
    public static ClipSourceException unsupported() {
        return new ClipSourceException(422, "UNSUPPORTED_MEDIA", "지원하지 않는 영상 형식입니다.");
    }
}
