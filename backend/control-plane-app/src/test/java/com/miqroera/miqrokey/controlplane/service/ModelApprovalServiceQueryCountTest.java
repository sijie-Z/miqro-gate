package com.miqroera.miqrokey.controlplane.service;

import com.miqroera.miqrokey.controlplane.config.ApprovalProperties;
import com.miqroera.miqrokey.controlplane.dto.ModelApprovalView;
import com.miqroera.miqrokey.domain.model.ModelApproval;
import com.miqroera.miqrokey.domain.model.ModelApprovalStatus;
import com.miqroera.miqrokey.domain.model.Project;
import com.miqroera.miqrokey.domain.model.ProjectStatus;
import com.miqroera.miqrokey.domain.model.User;
import com.miqroera.miqrokey.domain.model.UserRole;
import com.miqroera.miqrokey.domain.model.UserStatus;
import com.miqroera.miqrokey.domain.model.VirtualKey;
import com.miqroera.miqrokey.domain.model.VirtualKeyPurpose;
import com.miqroera.miqrokey.domain.model.VirtualKeyStatus;
import com.miqroera.miqrokey.domain.repository.ModelApprovalRepository;
import com.miqroera.miqrokey.domain.repository.ProjectProviderGrantRepository;
import com.miqroera.miqrokey.domain.repository.ProjectRepository;
import com.miqroera.miqrokey.domain.repository.UserRepository;
import com.miqroera.miqrokey.domain.repository.VirtualKeyRepository;
import com.miqroera.miqrokey.domain.service.AuditService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

/**
 * Cost guard for the model-approval list paths (PH8b): rendering N rows must
 * not issue a repository lookup per row. {@code /api/v1/me/model-approvals} is
 * a user-facing endpoint with no page size — the dashboard calls it on every
 * overview load — so a per-row lookup makes the query count a function of how
 * much history the caller happens to have accumulated.
 *
 * <p>
 * The assertion is deliberately relational (5 rows must cost the same as 1 row)
 * rather than an absolute number: it pins the shape of the fix (batch the
 * lookups) without freezing an exact implementation.
 * </p>
 */
class ModelApprovalServiceQueryCountTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID CALLER = UUID.randomUUID();
    private static final User USER = new User(CALLER, TENANT, "alice", "Alice Zhang", new byte[]{1}, UserRole.USER,
            UserStatus.ACTIVE, false, 0, null, null, 1L, Instant.now(), Instant.now());

    /**
     * Three keys spread over two projects — the distinct-ID set a batch fix must
     * collapse.
     */
    private static final List<UUID> KEY_IDS = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    private static final List<UUID> PROJECT_IDS = List.of(UUID.randomUUID(), UUID.randomUUID());

    @Test
    @DisplayName("listMine costs the same for 5 rows as for 1 row")
    void listMineDoesNotScaleRepositoryLookupsWithRowCount() {
        Harness oneRow = harness(1);
        int oneBefore = oneRow.repositoryInvocations();
        List<ModelApprovalView> single = oneRow.service().listMine(USER);
        int oneCalls = oneRow.repositoryInvocations() - oneBefore;

        Harness fiveRows = harness(5);
        int fiveBefore = fiveRows.repositoryInvocations();
        List<ModelApprovalView> many = fiveRows.service().listMine(USER);
        int fiveCalls = fiveRows.repositoryInvocations() - fiveBefore;

        System.out.printf("listMine repository lookups: 1 row -> %d, 5 rows -> %d%n", oneCalls, fiveCalls);

        assertThat(single).hasSize(1);
        assertThat(many).hasSize(5);
        assertRendered(many.get(0), 0);
        assertRendered(many.get(4), 4);

        assertThat(fiveCalls).as("repository lookups for 5 approvals vs 1").isEqualTo(oneCalls);
    }

    @Test
    @DisplayName("listQueue costs the same for 5 rows as for 1 row")
    void listQueueDoesNotScaleRepositoryLookupsWithRowCount() {
        Harness oneRow = harness(1);
        int oneBefore = oneRow.repositoryInvocations();
        List<ModelApprovalView> single = oneRow.service().listQueue(USER, ModelApprovalStatus.PENDING, 50, null, null);
        int oneCalls = oneRow.repositoryInvocations() - oneBefore;

        Harness fiveRows = harness(5);
        int fiveBefore = fiveRows.repositoryInvocations();
        List<ModelApprovalView> many = fiveRows.service().listQueue(USER, ModelApprovalStatus.PENDING, 50, null, null);
        int fiveCalls = fiveRows.repositoryInvocations() - fiveBefore;

        System.out.printf("listQueue repository lookups: 1 row -> %d, 5 rows -> %d%n", oneCalls, fiveCalls);

        assertThat(single).hasSize(1);
        assertThat(many).hasSize(5);
        assertRendered(many.get(0), 0);

        assertThat(fiveCalls).as("repository lookups for 5 queue rows vs 1").isEqualTo(oneCalls);
    }

    private static void assertRendered(ModelApprovalView view, int index) {
        UUID keyId = KEY_IDS.get(index % KEY_IDS.size());
        UUID projectId = projectIdFor(index % KEY_IDS.size());
        assertThat(view.virtualKeyId()).isEqualTo(keyId);
        assertThat(view.keyName()).isEqualTo("key-" + keyId);
        assertThat(view.keyDisplay()).isEqualTo("mk_" + keyId + "…abcd");
        assertThat(view.projectTag()).isEqualTo("tag-" + projectId);
        assertThat(view.requesterName()).isEqualTo("Alice Zhang");
        assertThat(view.reviewedByName()).isNull();
    }

    private static UUID projectIdFor(int keyIndex) {
        return PROJECT_IDS.get(keyIndex % PROJECT_IDS.size());
    }

    /**
     * A service instance plus the four repository mocks whose calls it is built
     * from.
     */
    private record Harness(ModelApprovalService service, List<Object> repositories) {

        int repositoryInvocations() {
            int total = 0;
            for (Object repository : repositories) {
                total += Mockito.mockingDetails(repository).getInvocations().size();
            }
            return total;
        }
    }

    private static Harness harness(int rows) {
        ModelApprovalRepository approvalRepository = Mockito.mock(ModelApprovalRepository.class);
        VirtualKeyRepository keyRepository = Mockito.mock(VirtualKeyRepository.class);
        ProjectRepository projectRepository = Mockito.mock(ProjectRepository.class);
        UserRepository userRepository = Mockito.mock(UserRepository.class);

        List<ModelApproval> approvals = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            UUID keyId = KEY_IDS.get(i % KEY_IDS.size());
            approvals.add(new ModelApproval(UUID.randomUUID(), TENANT, keyId, "claude-sonnet-5", CALLER,
                    ModelApprovalStatus.PENDING, null, "need it", null, 1L, Instant.now(), Instant.now()));
        }
        List<VirtualKey> keys = new ArrayList<>();
        List<Project> projects = new ArrayList<>();
        for (int i = 0; i < KEY_IDS.size(); i++) {
            UUID keyId = KEY_IDS.get(i);
            UUID projectId = projectIdFor(i);
            VirtualKey key = new VirtualKey(keyId, TENANT, "pk_" + keyId, new byte[]{2}, "mk_" + keyId, "abcd", CALLER,
                    projectId, UUID.randomUUID(), UUID.randomUUID(), VirtualKeyPurpose.CLAUDE_CODE, "key-" + keyId,
                    null, VirtualKeyStatus.ACTIVE, Instant.now(), null, null, null, 1L);
            Project project = new Project(projectId, TENANT, "proj-" + i, "Project " + i, null, null,
                    ProjectStatus.ACTIVE, "tag-" + projectId, 1L, Instant.now(), Instant.now());
            keys.add(key);
            projects.add(project);
            when(keyRepository.findById(keyId)).thenReturn(Optional.of(key));
            when(projectRepository.findById(projectId)).thenReturn(Optional.of(project));
        }
        when(userRepository.findById(CALLER)).thenReturn(Optional.of(USER));
        // The batched lookups the list paths resolve rows through.
        when(keyRepository.findAllByIds(any())).thenReturn(keys);
        when(projectRepository.findAllByIds(any())).thenReturn(projects);
        when(userRepository.findAllByIds(any())).thenReturn(List.of(USER));
        if (rows == 1) {
            when(approvalRepository.findAllByRequestedBy(CALLER)).thenReturn(List.of(approvals.get(0)));
            when(approvalRepository.findPage(any(), anyInt(), any(), any())).thenReturn(List.of(approvals.get(0)));
        } else {
            when(approvalRepository.findAllByRequestedBy(CALLER)).thenReturn(approvals);
            when(approvalRepository.findPage(any(), anyInt(), any(), any())).thenReturn(approvals);
        }

        ModelApprovalService service = new ModelApprovalService(approvalRepository, keyRepository,
                Mockito.mock(ProjectProviderGrantRepository.class), projectRepository, userRepository,
                Mockito.mock(NamedParameterJdbcTemplate.class), Mockito.mock(ApprovalProperties.class),
                Mockito.mock(AuditService.class), Mockito.mock(RouteRefreshPublisher.class),
                Mockito.mock(AlertEventDispatcher.class));
        return new Harness(service, List.of(approvalRepository, keyRepository, projectRepository, userRepository));
    }
}
