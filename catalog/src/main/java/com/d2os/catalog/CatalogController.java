package com.d2os.catalog;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only catalog listing (T061, contracts/api.yaml — catalog tag). */
@RestController
public class CatalogController {

  private final DefinitionAssetRepository repository;

  public CatalogController(DefinitionAssetRepository repository) {
    this.repository = repository;
  }

  @GetMapping("/api/v1/catalog/definitions")
  public ResponseEntity<List<DefinitionSummary>> list(
      @RequestParam(required = false) String type, @RequestParam(required = false) String key) {

    List<DefinitionSummary> all =
        repository.findAll().stream()
            .filter(d -> "Published".equals(d.getStatus()))
            .filter(d -> type == null || type.equals(d.getType()))
            .filter(d -> key == null || key.equals(d.getKey()))
            .map(
                d ->
                    new DefinitionSummary(
                        d.getKey(), d.getVersion(), d.getType(), d.getStatus(), d.getChecksum()))
            .toList();
    return ResponseEntity.ok(all);
  }

  public record DefinitionSummary(
      String key, String version, String type, String status, String checksum) {}
}
