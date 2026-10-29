package com.retail.oms.pricing.infrastructure.persistence;

import com.retail.oms.pricing.domain.ProductPrice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductPriceRepository extends JpaRepository<ProductPrice, String> {
}
