package com.d2os.studio;

import com.d2os.catalog.DefinitionAssetRepository;
import com.d2os.governance.DeltaReport;
import com.d2os.governance.DeltaReportRepository;
import com.d2os.governance.GateInstance;
import com.d2os.governance.GateInstanceRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * The D4/architecture-board review page (tasks.md T015, FR-005): renders a gate's {@link
 * DeltaReport} as first-class review content — a diff2html island is meant to mount over the raw
 * unified-diff text this controller passes into the model, the exact same "inert until T002 vendors
 * the real JS" posture {@code draft-edit.html}'s dmn-js container already documents (T011).
 *
 * <p>A dedicated page controller (plan.md/T015 offered either this or a {@code StudioPageController}
 * method) — kept separate because it reads from BOTH {@code catalog} ({@link
 * DefinitionAssetRepository}, to resolve the gate's subject) and {@code governance} ({@link
 * GateInstanceRepository}/{@link DeltaReportRepository}) directly, rather than adding those
 * dependencies onto {@code StudioPageController}, whose existing constructor is purely
 * catalog-facing (T011).
 */
@Controller
@RequestMapping("/studio/review")
public class ReviewPageController {

    private final GateInstanceRepository gateInstanceRepository;
    private final DeltaReportRepository deltaReportRepository;
    private final DefinitionAssetRepository definitionAssetRepository;

    public ReviewPageController(GateInstanceRepository gateInstanceRepository,
                                DeltaReportRepository deltaReportRepository,
                                DefinitionAssetRepository definitionAssetRepository) {
        this.gateInstanceRepository = gateInstanceRepository;
        this.deltaReportRepository = deltaReportRepository;
        this.definitionAssetRepository = definitionAssetRepository;
    }

    /** {@code GET /studio/review/{gateId}} — the gate detail + rendered diff (T015). */
    @GetMapping("/{gateId}")
    public String review(@PathVariable UUID gateId, Model model) {
        GateInstance gate = gateInstanceRepository.findById(gateId)
                .orElseThrow(() -> new NoSuchElementException("gate " + gateId));
        model.addAttribute("gate", gate);

        if (gate.getSubjectType() == GateInstance.GateSubjectType.DEFINITION_VERSION && gate.getSubjectId() != null) {
            definitionAssetRepository.findById(gate.getSubjectId())
                    .ifPresent(d -> model.addAttribute("subject", d));
        }

        DeltaReport report = gate.getDeltaReportId() == null ? null
                : deltaReportRepository.findById(gate.getDeltaReportId()).orElse(null);
        model.addAttribute("deltaReport", report);
        model.addAttribute("diffContent", report == null ? "" : report.getDiffContent());
        return "studio/review";
    }
}
