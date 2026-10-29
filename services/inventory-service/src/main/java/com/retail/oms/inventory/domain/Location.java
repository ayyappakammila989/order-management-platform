package com.retail.oms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "location")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Location {

    @Id
    @Column(name = "location_id")
    private String locationId;

    @Enumerated(EnumType.STRING)
    private LocationType type;

    private String name;

    private String zip;

    public Location(String locationId, LocationType type, String name, String zip) {
        this.locationId = locationId;
        this.type = type;
        this.name = name;
        this.zip = zip;
    }
}
