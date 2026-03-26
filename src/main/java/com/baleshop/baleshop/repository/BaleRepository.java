package com.baleshop.baleshop.repository;

import com.baleshop.baleshop.model.Bale;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BaleRepository extends JpaRepository<Bale, Integer> {
}