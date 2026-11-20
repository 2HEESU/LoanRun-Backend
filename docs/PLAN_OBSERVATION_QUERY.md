# 실제 월 관측 실적의 계획 입력 조회

Issue #134의 `PlanObservationQueryService.current(user,loan)`은 실제 원천을 조회하여
`PlanProjectionBasisPolicy.Observation` 목록을 만든다. 새 HTTP API·공통 포트·DB 변경은 없다.

## 조회 범위와 확인 시각

소유 대출을 먼저 조회하고 서버 Clock의 한 Instant를 캡처한다. Window는 그 시각, KST 기준일,
등록일, 현재 상환 방식과 관측 입력을 보존한다. 직전6개 완료 달력 월에서 대출 **등록월 이후**만
읽는다. 현재월·미래월·등록 전 월을 넣지 않으며 오래된 월로6개를 채우지 않는다. 등록은 계약
start_date가 아니라 실제 loan.created_at이다. 등록한 달에는 완료월 입력이 없을 수 있다.

이 서비스는 현재 시점 계산 입력이다. 과거 as_of의 약정이나 확인 근거 이력을 복원하는 기능이
아니며 호출자가 Window.asOf/knownAt과 현재 계산 입력의 일치 및 활성 대출 여부를 확인해야 한다.
소유자의 PAID/DELETED 보존 자료도 내부 조회할 수 있으나 이를 활성 계산 대상으로 선언하지 않는다.
한 READ_ONLY/REPEATABLE_READ 트랜잭션에서 읽는다. 상위 트랜잭션이 있으면 상위 격리 수준이 적용된다.

## 납부와 적립을 별도로 확인

- 분할형은 기존 PaymentObservationQueryService의 검증된 `extraPrincipal`만 전달한다.
  정기 원금·이자·수수료·현금을 추가 원금으로 섞지 않는다.
- BULLET은 새 SavingObservationQueryService의 검증된 `additions`만 전달한다.
  초기 자산·적립 잔액·적립금 사용은 월 적립 실적이 아니다. 납부 확인 근거를 대신 사용하지 않는다.
- 두 조회 모두 `observeAt(...,knownAt)`으로 같은 시각을 사용한다. 조회 도중 도착한 확인 근거는
  이전 시각의 Window에 들어오지 않는다. 이 메서드는 과거 원장 행을 복원하는 조회가 아니다.

적립 관측의 내부 SavingObservationSource는 명시적으로 저장된 완료/실제0원 확인 근거를 읽는
확장 지점이다. 실제 입력·저장 workflow와 기본 공급자는 설치하지 않는다. 클라이언트 JSON/header로
직접 확인 근거를 주입하는 경로도 없다. 공급자는 권한과 커밋된 전체 월 기록 완료를 확인해야 한다.

| 검증 결과 | observed | 계획 입력 amount |
| --- | --- | --- |
| 확인 근거 없음 (빈/양수 원장 모두) | false | null |
| 일치하는 명시적 완료 근거 | true | 실제 합계 (0원 포함) |
| 실제0원 확인 + 빈 원장 | true | 0 |
| 오래된 fingerprint/양수 원장에0원 주장/미래 확인 | false | null |
| 해당 월 시작 전에 미리 만든 확인 | false | null (그 월이 나중에 도래해도 유효해지지 않음) |
| 다른 사용자/대출/월 근거 또는 잘못된 공급자 응답 | 실패 | SERVICE_UNAVAILABLE |

실제 공급자가 없는 기본 런타임은 false/null을 유지한다. 계획 입력 선택기는 관측이 없으면 저장
계획으로 fallback할 수 있지만, 깨진 공급자·overflow 등 조회 실패를 정상 빈 관측으로 바꾸지 않는다.

## 적립 원천 fingerprint

사용자/대출/월 prefix와 해당 월 적립의 id·saved_at·amount를 id순으로 SHA-256에 반영한다.
합계와 hash는 같은 소유자 확인 SELECT에서 만든다. 같은 날 여러 적립은 모두 포함한다.
금액/날짜 변경·삭제·같은 금액으로 새 id 재등록은 기존 근거를 무효화한다. 메모만 수정하거나
다른 월 기록·초기 자산·납부/적립금 사용이 바뀌어도 **월 적립액** 근거를 변경하지 않는다.
이는 자산 잔액이나 전체 재정 기록 완료의 확인을 뜻하지 않는다.

fingerprint 자체는 사용자의 완료 확인이 아니다. Snapshot.recorded 합계는 확인 여부와 별개로
원천 사실을 보존하며, 정상 confirmed actuals만 계획 입력으로 전달한다. 미기록을0원으로 채우지 않는다.

## 검증 및 남은 연결

실제 PostgreSQL 원천과 기존 계획 입력 선택기를 연결해 확인0원·평균·지표 분리, 소유권,
원천 변경 무효화, KST 경계/한 시각 캡처, 읽기 전용·revision 보존과 합산 범위를 검증한다.
진짜 완료 확인 저장/입력 workflow, 현재/과거 금융 입력 캡처, 계획 조회/표시 포트·HTTP API,
실제 V4 적용은 후속이다. 테스트의 명시적 근거 공급자는 운영 기본 구현이 아니다.
