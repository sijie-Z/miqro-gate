package com.miqroera.miqrokey.controlplane.controller;

import com.miqroera.miqrokey.controlplane.dto.ModelApprovalView;
import com.miqroera.miqrokey.controlplane.dto.SubmitModelApprovalRequest;
import com.miqroera.miqrokey.controlplane.security.UserContext;
import com.miqroera.miqrokey.controlplane.service.ModelApprovalService;
import com.miqroera.miqrokey.domain.model.User;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Self-service model-approval endpoints (原始设计文档 §8.2): submit a request for an
 * additional model on one of the caller's keys and list the caller's own
 * requests. Approve/reject lives on the admin side.
 */
@RestController
@RequestMapping("/api/v1/me/model-approvals")
public class MeModelApprovalController {

    private final ModelApprovalService modelApprovalService;
    private final UserContext userContext;

    public MeModelApprovalController(ModelApprovalService modelApprovalService, UserContext userContext) {
        this.modelApprovalService = modelApprovalService;
        this.userContext = userContext;
    }

    @PostMapping
    public ResponseEntity<ModelApprovalView> submit(@Valid @RequestBody SubmitModelApprovalRequest request,
            HttpServletRequest httpReq) {
        ModelApprovalView view = modelApprovalService.submit(user(), request, requestId(httpReq));
        return ResponseEntity.status(HttpStatus.CREATED).body(view);
    }

    @GetMapping
    public List<ModelApprovalView> listMine() {
        return modelApprovalService.listMine(user());
    }

    private User user() {
        return userContext.getUser();
    }

    private static String requestId(HttpServletRequest request) {
        String header = request.getHeader("X-Request-Id");
        return header != null && !header.isBlank() ? header : UUID.randomUUID().toString();
    }
}
