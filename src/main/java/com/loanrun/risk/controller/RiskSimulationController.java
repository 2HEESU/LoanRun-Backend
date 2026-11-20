package com.loanrun.risk.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.loanrun.common.api.ApiResponse;
import com.loanrun.common.contract.CurrentUserProvider;
import com.loanrun.common.error.ApiException;
import com.loanrun.common.validation.JsonPatch;
import com.loanrun.risk.dto.RiskDtos.*;
import com.loanrun.risk.service.RiskSimulationService;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/risks")
public class RiskSimulationController {
    private final CurrentUserProvider currentUser;
    private final RiskSimulationService service;
    public RiskSimulationController(CurrentUserProvider currentUser,RiskSimulationService service){this.currentUser=currentUser;this.service=service;}
    @PostMapping("/simulate/")
    public ApiResponse<Simulation> simulate(@RequestBody JsonNode body){
        currentUser.requirePro();
        try {
            JsonPatch.fields(body,"scenario_type","loan_ids");var type=JsonPatch.enumeration(body,"scenario_type",ScenarioType.class);
            List<Long> selected=new ArrayList<>();Set<Long> seen=new HashSet<>();
            if(body.has("loan_ids")) {
                if(!body.get("loan_ids").isArray()) throw ApiException.validation("loan_ids","배열이어야 합니다.");
                for(JsonNode id:body.get("loan_ids")) {
                    if(!id.isIntegralNumber()||!id.canConvertToLong()||id.longValue()<=0||!seen.add(id.longValue()))
                        throw ApiException.validation("loan_ids","양의 대출 ID를 중복 없이 입력해 주세요.");
                    selected.add(id.longValue());
                }
            }
            return ApiResponse.success("RISK_SIMULATION_SUCCESS","금리·소득 변화 시뮬레이션 처리를 완료했습니다.",service.simulate(currentUser.currentUserId(),type,List.copyOf(selected)));
        } catch(ApiException ex){
            if(!ex.code().equals("VALIDATION_ERROR")) throw ex;
            throw new ApiException(HttpStatus.BAD_REQUEST,"SIMULATION_VALIDATION_ERROR","시뮬레이션 입력값을 확인해 주세요.",ex.errors());
        } catch(ArithmeticException ex){throw new ApiException(HttpStatus.BAD_REQUEST,"SIMULATION_VALIDATION_ERROR","시뮬레이션 입력값을 확인해 주세요.");}
    }
}
