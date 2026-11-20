# 상환 계획 조회 API #20

GET /api/repayment-plans/는 소유 계획을 공통 PlanView 형식으로 반환한다. loan_id 생략 시 전체
ACTIVE, 지정 시 본인 비삭제 대출이다. 현재 as_of만 지원하며 과거/미래 약정 부재는503이다.
잘못된 형식/중복/알 수 없는 query는400 VALIDATION_ERROR다.

기존 원천·현재 예상 완납·성과를 재사용한다. 과거 약정/관측 부족은 null/INSUFFICIENT이며
현재 약정으로 과거를 대체하지 않는다. 예상일 하나라도 불명확하면 전체 예상일도 null이다.
월 배분은 실제 BudgetQueryPort override를 받는다. 명시0원을 보존하고 장기 예상일은 바꾸지 않는다.
override의 누적 적용 근거가 없으면 누적 gap/track은 null로 유지한다. 가계 recurring_cap을 대출마다
재사용하지 않으며 다중 대출의 개별 부족액은 배분 근거가 없어 null이다. 공급자 부재/오류는503이다.

FREE는 전략/우선순위를 숨겨 LOCKED, PRO 0~1개는 SINGLE_LOAN이다. PRO 다중은 실제
PlanQueryPort.overallState/allocations로 EMPTY/AVAILABLE을 구분한다. FREE의 존재 flag false는
접근 마스킹이며 저장 원본을 제거하지 않는다. 전체 계획/실제 진행 비교는 가중 집계 근거가 없어
null/INSUFFICIENT다. 최초 원금 대비 저장 잔액 감소는 기존 목록과 같은 기준으로 제공한다.
scenarios는 후보 생성 요청이 아니므로 빈 배열이다. data_revision은 사용자 revision/KST 기준일이다.

실제 V4·BudgetQueryPort가 없는 현재 비어 있지 않은 조회는503이다. 테스트만 SQL proposal과 명시적
예산 공급자를 사용한다. DB/Flyway/공통 포트 부분 구현/금융 writes는 없다. HTTP 경로 구현과 실제
외부 공급/과거 조회/전체 성과 집계 완료를 구분한다.
