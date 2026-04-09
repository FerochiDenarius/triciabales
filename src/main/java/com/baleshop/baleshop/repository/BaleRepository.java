package com.baleshop.baleshop.repository;

import com.baleshop.baleshop.model.Bale;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BaleRepository extends JpaRepository<Bale, Integer> {

    List<Bale> findBySellerId(Long sellerId);
}
