# 계획 저장 (#21)

PUT `/api/repayment-plans/`는 필수 Idempotency-Key와 preview_version을 받는다. 사용자 잠금 아래 동일 입력의 공통 미리보기를 다시 계산하고 사용자/revision/KST 날짜/입력에 연결된 token을 검증한다. 원래 기준 버전은 보존하고 다음 미도래 회차의 새 불변 버전을 append한다. 본문·응답 멱등 재사용, 다른 본문 충돌, PLAN_PREVIEW_STALE/STRATEGY_STALE, 소유권/PRO 검증을 유지한다.

계획 버전, 전체 전략 적용 이벤트, 예산 재계산, 한 번의 revision 증가와 멱등 응답은 한 트랜잭션이다. 당월 override나 실제 납부·등록 원본은 변경하지 않는다. 다중 전략 최초 반영 시에만 전체 계획 exists=true이며 후속 직접 수정은 MANUAL로 유지한다. 저장 원천 전용 내부 SavedPlanQueryService는 예산·성과/표시 서비스를 역호출하지 않는다. 공통 PlanQueryPort 런타임 어댑터는 DB baseline 합의 후 연결하며 기존 V2 적립 조회 동작을 바꾸지 않는다. 내부 currentPlans는 현재 기준, plannedAllocations는 현재/미래 월을 지원한다. 과거 active 상태/약정이 입증되지 않는 시점은 503이다.

V4 불변 버전 SQL과 `docs/sql/plan_application_events_proposal.sql`은 격리 테스트용 제안이다. 후자는 전체 적용 상태·배분 우선순위를 append-only 코드로 보존한다. 런타임 Flyway 활성화/실제 DB 적용 없음. 예산 재계산/필수 저장소 부재·실패는 503 및 전체 롤백. 새 환경변수/의존성 없음.
