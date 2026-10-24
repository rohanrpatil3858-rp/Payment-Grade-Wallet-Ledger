package com.wallet.fraud;

import com.wallet.transfer.PeerTransferResult;
import com.wallet.transfer.TransferService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/risk")
public class RiskAdminController {

    private final TransferService transferService;
    private final JdbcTemplate jdbcTemplate;

    public RiskAdminController(TransferService transferService, JdbcTemplate jdbcTemplate) {
        this.transferService = transferService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Held and flagged transfers awaiting an admin's attention. */
    @GetMapping("/queue")
    public ResponseEntity<Map<String, Object>> queue() {
        List<Map<String, Object>> held = jdbcTemplate.queryForList(
                "SELECT id, type, amount, note, created_at FROM transfers WHERE status = 'held' ORDER BY created_at");
        List<Map<String, Object>> flagged = jdbcTemplate.queryForList(
                "SELECT rf.transfer_id, rf.rule_name, rf.label, rf.explanation, rf.created_at "
                        + "FROM risk_flags rf ORDER BY rf.created_at DESC");
        return ResponseEntity.ok(Map.of("held", held, "flagged", flagged));
    }

    /** Unified decision endpoint: {"decision": "release"} or {"decision": "reverse"}. */
    @PostMapping("/{transferId}/decision")
    public ResponseEntity<Map<String, Object>> decide(@PathVariable long transferId,
                                                       @RequestBody Map<String, String> body) {
        String decision = body.get("decision");
        if ("release".equals(decision)) {
            PeerTransferResult result = transferService.releaseHeldTransfer(transferId);
            return ResponseEntity.ok(Map.of("transferId", result.transferId(), "status", "completed"));
        }
        if ("reverse".equals(decision)) {
            transferService.reverseHeldTransfer(transferId);
            return ResponseEntity.ok(Map.of("transferId", transferId, "status", "reversed"));
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "INVALID_DECISION", "message", "decision must be 'release' or 'reverse'"));
    }

    @PostMapping("/{transferId}/release")
    public ResponseEntity<Map<String, Object>> release(@PathVariable long transferId) {
        PeerTransferResult result = transferService.releaseHeldTransfer(transferId);
        return ResponseEntity.ok(Map.of("transferId", result.transferId(), "status", "completed"));
    }

    @PostMapping("/{transferId}/reverse")
    public ResponseEntity<Map<String, Object>> reverse(@PathVariable long transferId) {
        transferService.reverseHeldTransfer(transferId);
        return ResponseEntity.status(HttpStatus.OK).body(Map.of("transferId", transferId, "status", "reversed"));
    }
}
