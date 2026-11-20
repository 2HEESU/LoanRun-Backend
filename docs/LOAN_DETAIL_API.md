# 대출 상세 조회 API #15

GET /api/loans/{loan_id}/는 기존 목록과 같은 공개 대출 필드(estimated_monthly_principal, estimated_monthly_interest,
estimated_remaining_interest, next_due_date, target_payoff_date, estimated_payoff_date 포함), 현재 계획, 잔여 이자/수수료 및 실제
저장된 다음 미충족 회차를 조합한다. PAID는 다음 납부가 null이며 DELETED/타인/없는 ID는404다.
비공개/다른 대출의 잘못된 원천을 먼저 파싱하지 않는다. GET preview는 기존405를 유지한다.

정기 배분은 기존 StoredDueQueryService로 계산한다. 부분 납부·미기록을 PAID로 바꾸지 않고
실제 추가 원금과 정기 충당 금액을 구분한다. 저장된 다음 회차가 없으면 임의 due ID를 만들지 않는다.
다른 월 회차의 추가 목표·전체 예정 금액은 해당 월의 계획/override 근거가 없어 null이다.
계획 부족·동결 회차 제약은 잔여 이자/수수료를 null로 유지하고, 월 예산/V4 부재는503이다.

읽기 전용이며 기존 기본 계획·납부·적립·revision은 변경하지 않는다. 원래 명세의 snake_case와
LOAN_DETAIL_SUCCESS/200을 사용한다. DB/Flyway/환경변수/의존성 변경은 없다.
