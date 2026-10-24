package com.wallet.statement;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class StatementController {

    private final StatementQaService statementQaService;

    public StatementController(StatementQaService statementQaService) {
        this.statementQaService = statementQaService;
    }

    @PostMapping("/users/{id}/statement/ask")
    public ResponseEntity<StatementAnswer> ask(@PathVariable("id") long userId, @RequestBody Map<String, String> body) {
        StatementAnswer answer = statementQaService.ask(userId, body.get("question"));
        return ResponseEntity.ok(answer);
    }
}
