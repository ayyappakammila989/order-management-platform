package com.retail.oms.inventory.infrastructure.persistence;

import com.retail.oms.inventory.domain.Stock;
import com.retail.oms.inventory.domain.StockId;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockRepository extends JpaRepository<Stock, StockId> {

    /**
     * First pass of a reservation (architecture 13, "inventory hot rows"): lock the rows of this SKU that still have
     * free stock, but SKIP the rows another transaction holds. A popular SKU at one location then does not make every
     * order queue behind the others: they take stock from the other locations instead. Locked in location order.
     */
    @Query(value = """
            SELECT * FROM stock WHERE sku = :sku AND on_hand - reserved - safety_stock > 0
            ORDER BY location_id FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<Stock> lockFreeRowsSkipLocked(@Param("sku") String sku);

    /** Second pass, only when the first did not find enough: wait (up to the lock timeout) for the skipped rows. */
    @Query(value = "SELECT * FROM stock WHERE sku = :sku ORDER BY location_id FOR UPDATE", nativeQuery = true)
    List<Stock> lockAllRows(@Param("sku") String sku);

    @Query(value = "SELECT * FROM stock WHERE sku = :sku AND location_id = :locationId FOR UPDATE", nativeQuery = true)
    Optional<Stock> lockRow(@Param("sku") String sku, @Param("locationId") String locationId);

    List<Stock> findBySkuOrderByLocationId(String sku);
}
