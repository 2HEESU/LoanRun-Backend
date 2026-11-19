# 월별 납부 일정 (#26)

GET `/api/payments/schedule/?month=YYYY-MM&loan_id=...`는 실제 저장 회차 ID/약정 필요액과 정기 배분으로 5가지 상태를 산출한다. EXTRA는 정기 충족에 포함하지 않는다. 달력은 실제 회차별 REGULAR_DUE를 표시한다. 예정액/잔여액/회차 수는 예정일 월, 기록된 납부/추가 원금/적립은 실제 paid_at/saved_at 월로 집계한다. 같은 대출의 월 목표/실적을 복수 회차 합계에 중복 합산하지 않는다.

알 수 없는 계획 목표는 null이며 분할의 적립 0/BULLET의 추가 원금 0 같은 구조적 값만 0이다. 실제 PlanPresentationPort와 BudgetQueryPort 공급 시 해당 월 계획·override를 조합한다. 저장 회차가 생성되지 않은 활성 계약 월은 빈 일정/가짜 ID를 반환하지 않고 503이다. 과거 계약이 없는 월은 실제 빈 일정이다. 삭제 대출 미래는 제외하고 보존된 과거 회차는 읽는다.

400 INVALID_MONTH, 소유자 없는 대출 404. GET은 회차를 생성하지 않고 원본/revision을 바꾸지 않는다. 월별 actual_repayment는 기록된 총액이고 월 전체 관측 완료를 뜻하지 않는다. observed 기반 확정 실적은 #27에서 구분한다. Flyway/환경변수/의존성 변경 없음.
