# 상환 계획 공통 미리보기 (#22)

`POST /api/repayment-plans/preview/`의 직접 분기 `plans[]` 또는 PRO 전략 분기 `report_id + strategy_code + strategy_version`을 지원한다. 두 분기를 섞지 않는다. 200 `REPAYMENT_PLAN_PREVIEW_SUCCESS`에 current/proposed 계획, 총 이자·비용·월 부담, delta, 장기 배분, `data_revision`, `preview_version`을 반환한다. 활성 소유 대출만 대상이며 타인/삭제/완납은 404다. 입력은 400 `PLAN_VALIDATION_ERROR`, 계약상 역산 불가는 400 `PAYOFF_NOT_FEASIBLE`, PRO 부족은 403, 낡은 전략은 409 `STRATEGY_STALE`다.

## 직접 계획

- MATURITY: 분할 추가액 0, BULLET은 약정 만기까지 필요 적립 역산.
- PREFERENCE: FAST/BALANCED/STABLE = 가계 recurring cap의 80/50/0%, 원 단위 HALF_UP. 미수정 대출의 장기 배분을 먼저 차감한다. 여러 성향 입력은 동일 성향 및 명시적 `extra_monthly_payment` 배분을 받아 합계를 한 번 계산한 버킷과 대조한다. 대출마다 cap을 반복 적용하지 않는다.
- TARGET_DATE: 공통 엔진 `minimumExtra`로 최소 반복 월 현금을 역산한다. 재정 여유 초과는 200과 `RECURRING_CAP_EXCEEDED` 경고/단일 대출 부족액이며 계약상 불가능한 목표만 오류다.
- EXTRA_AMOUNT: 수수료 포함 월 추가 현금. MONTHLY_AMOUNT: 다음 기본 납부액을 뺀 나머지를 추가 현금으로 구분한다. 기본액보다 작으면 계산 불가다.
- BULLET은 MATURITY/TARGET_DATE와 선택 `planned_saving_amount`를 받으며 분할 추가상환 성향·금액 분기를 받지 않는다. 목표일까지 적립 준비와 실제 원금 상환을 구분하고, 적립했다고 조기 원금을 자동 차감하지 않는다. 목표일까지 자금 부족은 `TARGET_NOT_MET`, 약정 만기 부족은 엔진 경고와 payoff null이다. 거치 중 양수 추가 목표는 거절한다.

## 비교와 보존

현재와 제안 모두 같은 현재 잔액·금리·기준일 및 저장/제안 장기 월 목표로 공통 엔진을 실행한다. current_plans의 미래 추정도 이번 비교에서는 FALLBACK_PLAN이며 실제 평균 속도와 제안 장기 금액을 섞어 delta를 계산하지 않는다. 전체 활성 가계를 비교하고 수정하지 않은 개별 장기 금액은 유지한다. 직접 다중 변경의 제안 전략은 PRO에서 MANUAL, FREE에서는 전략/우선순위를 숨긴다.

`delta.interest_saving`은 수수료 전 순수 이자 차이로 API 정의를 유지한다. 알려진 수수료는 추가 현금 안에서 원금과 나누며, 모르면 fee 및 확정할 수 없는 추가 원금은 null, 이자 비교는 gross다. root `monthly_burden`의 BULLET 만기 원금은 계획상 사용할 기존 적립 자산을 한 번 차감하고 새 적립을 별도로 더한다. 기본 정기 납부액은 완납 시 다른 대출의 추가 자금으로 재투입하지 않는다.

실제 당월 override는 바꾸지 않으며 제안 계획의 당월 예정 금액 표시에도 유지한다. proposed_allocations는 제안 장기 목표이며 당월 override 저장을 의미하지 않는다. 새 계획의 시작 잔액은 현재 원천이고 원래 계획 누적/실제 진행률은 새 계획에 복제하지 않고 null로 둔다. 실제 당월 기록은 기존 원본을 표시한다. 혼합 REGULAR의 비용을 추가 원금에 배분할 수 없으면 배분의 actual_amount도 null이다. 현재 계획/상세의 미확인 금액 null unboxing 경계도 함께 수정했다.

## 전략 및 버전

실제 `StrategyQueryPort.requireCurrentCandidate`에서 받은 후보의 사용자/리포트/코드/버전/revision/현재 KST 계산일을 확인한다. 후보의 실제 배분과 우선순위만 사용하며 반복/당월 capacity를 확인한다. 이자/위험/비례 전략은 기존 엔진의 pooled allocation 규칙으로 비교한다. 서버 원천이 없으면 임의 후보를 만들지 않고 503이다.

기본 적용일은 대출별 다음 미도래 회차다. 명시 적용일까지 모델 회차가 선행하는 경우 미래 잔액·약정 원천이 없어 503으로 보류한다. 부분/미충족 과거 회차 등 현재 원천도 안전하게 확정할 수 없으면 503이다. 실제 예산 및 V4 계획 원천이 필요하며 없으면 503이다. V4 적용은 계속 별도 DB 감사/순서 확정 대상이다.

일관된 읽기 트랜잭션에서 원본/계획/월 배분/revision/미리보기 행을 저장하지 않는다. preview_version은 사용자 + revision/KST 날짜 + 정렬된 입력 JSON의 SHA-256이며 같은 입력에 결정적이다. 향후 #21 저장은 같은 계산 및 revision/token 재검증이 필요하며 이번 PR은 저장 API를 추가하지 않는다. 마이그레이션·환경변수·의존성 변경 없음.
