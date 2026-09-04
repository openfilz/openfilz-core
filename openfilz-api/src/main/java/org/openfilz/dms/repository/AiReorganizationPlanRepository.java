package org.openfilz.dms.repository;

import org.openfilz.dms.entity.AiReorganizationPlan;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import java.util.UUID;

public interface AiReorganizationPlanRepository extends ReactiveCrudRepository<AiReorganizationPlan, UUID> {
}
