package com.reportix.aisolate.backend.application;

import com.reportix.aisolate.backend.domain.directory.Directory;
import com.reportix.aisolate.backend.domain.directory.DirectoryService;
import com.reportix.aisolate.backend.infrastructure.security.Access;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The company's people, read-only, for the AIsolate UI's Add users page: picked people become a
 * space's members or an airlock's users by their e-mail. 404 when no directory is configured.
 */
@Tag(name = "Directory", description = "The identity provider's people, searched read-only.")
@RestController
@RequestMapping("/v1/directory")
@RequiredArgsConstructor
public class DirectoryController {

  private final DirectoryService directory;

  @GetMapping("/people")
  @PreAuthorize(Access.DEVELOPER_ANYWHERE)
  public List<Directory.Person> search(@RequestParam(name = "q", defaultValue = "") String q) {
    return directory.search(q);
  }
}
