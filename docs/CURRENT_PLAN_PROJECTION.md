# 현재 대출 기준 계획 예상 완납 조회

Issue #136의 `PlanPayoffQueryService.current(user,loan)`은 실제 현재 대출 원천 → 계산 약정 →
저장 계획 → 확인된 월 관측 입력 → 기존 엔진을 연결한다. READ_ONLY/REPEATABLE_READ 내부 조회이며
HTTP API나 부분 PlanQueryPort/PlanPresentationPort를 설치하지 않는다. 상위 트랜잭션이 있으면
상위 격리 수준이 적용된다. 실제 V4 SQL은 활성화하지 않는다.

## 현재 약정과 회차

`CurrentLoanTermsQueryService`는 소유 대출의 현재 materialized 원금을 그대로 사용한다.
기존 납부 원금을 다시 빼지 않는다. 적립 자산은 기존 LoanSavingsCalculator로 읽는다.
현재 필드의 엔진 매핑/다음 날짜 처리는 LoanTermMapper로 분리하여 기존 대출 목록과 재사용한다.
대출 목록의 공개 응답이나 동결 회차 표시 방식은 바꾸지 않는다.

기존 DueStateCalculator로 정기 배분을 확인하여 완전히 충족한 회차만 다음 회차로 넘긴다.
이전 은행 입력 금액은 그 회차에만 적용하며 다음 회차에 재사용하지 않는다.
계산 엔진이 보존된 동결 의무를 정확히 표현할 수 없는 경우에는 terms=null과 내부 사유를 반환한다.

| 내부 사유 | 의미 |
| --- | --- |
| INACTIVE_OR_ZERO | PAID/DELETED 또는 원금0. 활성 상환 시뮬레이션을 만들지 않음 |
| PAST_DUE_UNSETTLED | 미충족 과거 회차 또는 지난 기준 회차의 근거 부재. 납부 완료를 가정하지 않음 |
| PARTIALLY_SETTLED_DUE | 아직 도래 전/당일 회차에 부분 배분이 있음. 전체 회차를 다시 청구하지 않음 |
| UNSUPPORTED_FROZEN_DUE | 동결 원금/이자와 엔진 불일치, 정기 수수료 또는 여러 미래 동결 회차를 단일 약정으로 표현할 수 없음 |
| NO_REMAINING_CYCLE | 원금은 있으나 이미 마지막 회차가 충족되어 후속 회차가 없음 |

정기 상태 UNRECORDED/UNPAID/PARTIAL을 임의로 PAID로 바꾸지 않는다. 추가 원금 때문에 현재
잔액이 줄었더라도 첫 동결 회차의 이자가 바뀌었다고 가정하지 않는다. 정확한 잔여 회차 replay와
동결 의무를 포함하는 엔진 입력 확장은 후속이다. 위 사유는 새 HTTP 오류 코드가 아니다.

## 계획 선택과 예측

원천 조회는 다음 계산 회차에 적용되는 저장 계획을 기준일에 알려진 자료로 선택한다.
아직 적용 전인 계획은 `projectWithUpcomingPlan` 경로로 **그 계획보다 이전인 계산 회차가 없을 때만**
미래 예측에 쓴다. 실제 기준일·원래 효력일·원금을 바꾸지 않는다. 기존 project 메서드는 계속
미래 효력 계획을 거절한다. 중간 회차를 생략하거나 새 계획을 그 회차에 소급 적용하지 않는다.

납부/적립 관측 입력은 PlanObservationQueryService를 재사용한다. 기준일 또는 상환 방식이 조합
중 달라지면503으로 재조회하도록 한다. 실제 완료 근거가 없으면 저장 목표 fallback이며 실제0원으로
가정하지 않는다. 이자·수수료·BULLET 만기 자금 부족 규칙은 기존 단일 엔진을 그대로 따른다.

원금 관측 입력 기능은 Team 1 내부 PrincipalRepaymentEngine 인터페이스로 표현한다. 기존 엔진이
그 인터페이스를 구현하며 공통 RepaymentEngine 및 Bean 타입/개수는 유지한다. 원금 기능이 없는
대체 엔진을 원금 입력에 조용히 사용하지 않는다. 별도 금융 알고리즘/중복 엔진 Bean은 만들지 않는다.

## 부재와 실제 연결 상태

계산 약정이 부족하면 projection=null, 적용 계획이 없으면 NO_APPLICABLE_PLAN,
월 입력을 선택할 수 없으면 MONTHLY_INPUT_UNAVAILABLE이다. 정상 조회 결과가 있어도 예상일은
만기 자금 부족/기간 등 기존 엔진 규칙에 따라 null일 수 있으므로 AVAILABLE을 완납 성공으로 해석하지 않는다.
V4 테이블/원천 조회 실패는503이며 V2-only로 전체 이력을 가장하지 않는다. 계산 가능한 대출에
V4가 미적용된 기본 런타임은 아직503이다. 테스트 DB만 SQL proposal을 사용한다.

조회는 원본·계획·revision을 변경하지 않는다. 실제 API #20/#15 연결, 부분/과거 미정산 회차 정산,
동결 의무 처리, 월별 override/예산과 실제 V4 적용은 후속이다. 현재 원천을 과거 기준으로 재사용하는
as_of 조회가 아니다. 새 HTTP 구현 개수는 늘지 않는다.

테스트는 실제 PostgreSQL/계획 저장소/관측 확인/단일 엔진으로 V2 fallback과 새 버전, 실제 추가
원금, BULLET 자산·이자 분리, 계산 불가 회차, 소유권, V4 부재와 금융 원본 불변을 검증한다.
