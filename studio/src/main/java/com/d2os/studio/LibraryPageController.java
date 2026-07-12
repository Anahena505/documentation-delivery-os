package com.d2os.studio;

import com.d2os.catalog.SubscriptionService;
import com.d2os.tenancy.WorkspaceContext;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Thymeleaf page for the Global-library browse + subscribe surface (T027, research R1, FR-015).
 * Same thin page-controller-over-JSON-service pattern {@link StudioPageController} establishes —
 * reuses {@link SubscriptionService#browseGlobal} directly, no separate read path.
 */
@Controller
@RequestMapping("/studio/library")
public class LibraryPageController {

  private final SubscriptionService subscriptionService;

  public LibraryPageController(SubscriptionService subscriptionService) {
    this.subscriptionService = subscriptionService;
  }

  @GetMapping
  public String browse(Model model) {
    model.addAttribute("entries", subscriptionService.browseGlobal(WorkspaceContext.require()));
    return "studio/library";
  }
}
