# 주문 DB와 예약 HTTP 경계: 로컬 실험

Java 21, Maven 3.9 이상에서 실행합니다.

```bash
mvn test
```

Spring Framework 6.2.19의 `@Transactional`, JDBC/H2 2.3.232, JDK 내장 `HttpServer`만 사용합니다. HTTP 서버는 테스트마다 `127.0.0.1`의 임의 포트에서 열리고 종료됩니다. 외부 예약·결제 API, 자격 증명, 운영 DB에는 연결하지 않습니다.

5개 JUnit 테스트는 HTTP 성공 후 로컬 롤백, 커밋 직전 콜백 실패, 효과 기록 후 응답 지연으로 인한 타임아웃, 동일 키로 확정 재시도, `REQUIRED`·`REQUIRES_NEW`·`NESTED`의 DB 결과를 검사합니다.

`beforeCommit` 예외는 실제 DB 커밋 I/O 실패를 재현하지 않습니다. H2 결과는 PostgreSQL·MySQL의 격리 수준이나 운영 연결 풀 동작을 입증하지 않습니다. 스텁의 멱등성은 예제에서 직접 구현한 동작이며, 실제 예약 서비스의 계약을 대신하지 않습니다.
