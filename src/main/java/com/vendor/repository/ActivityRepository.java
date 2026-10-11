package com.vendor.repository;

import com.vendor.model.Activity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ActivityRepository extends JpaRepository<Activity, Long> {

    List<Activity> findTop30ByClientIdOrderByAtDescIdDesc(Long clientId);

    List<Activity> findTop15ByOrderByAtDescIdDesc();
}
