# dev/

`dev-private.pem` là khóa RSA **chỉ dùng cho môi trường dev**, được commit cố ý để máy nào clone về cũng sinh được token.
Public key tương ứng nằm ở `src/main/resources/jwt/dev-public.pem`.

**Không dùng cặp khóa này ở production**: ai có repo đều ký được token. Production đặt
`JWT_PUBLIC_KEY_LOCATION` trỏ tới public key của identity provider (hoặc đổi sang `issuer-uri`).

Lấy token dev: chạy app với profile `dev` rồi gọi `GET /dev/token?userId=1`.

Sinh lại cặp khóa:

    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out dev/dev-private.pem
    openssl pkey -in dev/dev-private.pem -pubout -out src/main/resources/jwt/dev-public.pem
