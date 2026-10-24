package com.wallet.reconciliation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/reconciliation")
public class ReconciliationAdminController {

    private final ReconciliationService reconciliationService;
    private final JdbcTemplate jdbcTemplate;

    public ReconciliationAdminController(ReconciliationService reconciliationService, JdbcTemplate jdbcTemplate) {
        this.reconciliationService = reconciliationService;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostMapping("/run")
    public ResponseEntity<Map<String, Object>> triggerRun() {
        ReconciliationRunResult result = reconciliationService.run();
        if (result.skippedAlreadyRunning()) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("status", "skipped", "reason", "another run is already in progress"));
        }
        return ResponseEntity.accepted().body(Map.of(
                "runId", result.runId(),
                "issuesFound", result.issuesFound()));
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<Map<String, Object>> getRun(@PathVariable long id) {
        Map<String, Object> run = jdbcTemplate.queryForMap(
                "SELECT id, started_at, finished_at, status, checks_run, issues_found FROM reconciliation_runs WHERE id = ?",
                id);
        List<Map<String, Object>> issues = jdbcTemplate.queryForList(
                "SELECT check_name, entity_id, expected_value, actual_value FROM reconciliation_issues WHERE run_id = ? ORDER BY id",
                id);
        return ResponseEntity.ok(Map.of("run", run, "issues", issues));
    }
}
