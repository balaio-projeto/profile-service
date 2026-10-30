package br.com.balaio.profile.recovery;

public record ApiResponse<T>(T dados, ApiError erro) {
    public record ApiError(String codigo, String mensagem) {}
    public static <T> ApiResponse<T> ok(T data) { return new ApiResponse<>(data, null); }
    public static <T> ApiResponse<T> error(String code, String message) { return new ApiResponse<>(null, new ApiError(code, message)); }
}
