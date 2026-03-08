// Map instance
let map;
let alertMarkers = [];
let nearbyUserMarkers = [];

// Initialize map
function initMap() {
    // Create map centered on India
    map = L.map('map').setView([20.5937, 78.9629], 5);

    // Add Esri World Imagery tile layer
    L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', {
        attribution: 'Tiles © Esri — Source: Esri, i-cubed, USDA, USGS, AEX, GeoEye, Getmapping, Aerogrid, IGN, IGP, UPR-EGP, and the GIS User Community',
        maxZoom: 18
    }).addTo(map);

    console.log('Map initialized');
}

// Create custom marker icon for alerts
function createAlertIcon() {
    return L.divIcon({
        className: 'custom-alert-marker',
        html: `
            <div style="
                width: 40px;
                height: 40px;
                background: #ef4444;
                border: 3px solid #fff;
                border-radius: 50%;
                display: flex;
                align-items: center;
                justify-content: center;
                box-shadow: 0 4px 12px rgba(239, 68, 68, 0.5);
                font-size: 20px;
            ">⚠️</div>
        `,
        iconSize: [40, 40],
        iconAnchor: [20, 20]
    });
}

// Create custom marker icon for nearby users
function createUserIcon() {
    return L.divIcon({
        className: 'custom-user-marker',
        html: `
            <div style="
                width: 32px;
                height: 32px;
                background: #3b82f6;
                border: 2px solid #fff;
                border-radius: 50%;
                display: flex;
                align-items: center;
                justify-content: center;
                box-shadow: 0 4px 12px rgba(59, 130, 246, 0.5);
                font-size: 16px;
            ">👤</div>
        `,
        iconSize: [32, 32],
        iconAnchor: [16, 16]
    });
}

// Add alert markers to map
function addAlertMarkers(alerts) {
    // Clear existing alert markers
    alertMarkers.forEach(marker => map.removeLayer(marker));
    alertMarkers = [];

    alerts.forEach(alert => {
        if (alert.latitude && alert.longitude) {
            const marker = L.marker([alert.latitude, alert.longitude], {
                icon: createAlertIcon()
            }).addTo(map);

            // Create popup content
            const popupContent = `
                <div style="min-width: 200px;">
                    <strong style="color: #fbbf24; font-size: 14px;">Emergency Alert</strong><br>
                    <strong>Name:</strong> ${alert.user_name || 'Unknown'}<br>
                    <strong>Phone:</strong> ${alert.user_phone || 'N/A'}<br>
                    <strong>Type:</strong> ${alert.alert_type || 'N/A'}<br>
                    <strong>Time:</strong> ${formatTimeAgo(alert.created_at)}<br>
                    <button onclick="showAlertDetails('${alert.id}')" style="
                        margin-top: 8px;
                        width: 100%;
                        background: #fbbf24;
                        color: #000;
                        border: none;
                        padding: 6px;
                        border-radius: 4px;
                        cursor: pointer;
                        font-weight: 600;
                    ">View Details</button>
                </div>
            `;

            marker.bindPopup(popupContent);

            // Show popup on hover
            marker.on('mouseover', function (e) {
                this.openPopup();
            });

            // Store alert data with marker
            marker.alertData = alert;

            alertMarkers.push(marker);
        }
    });

    // Fit map to show all markers if any exist
    if (alertMarkers.length > 0) {
        const group = L.featureGroup(alertMarkers);
        map.fitBounds(group.getBounds().pad(0.1));
    }
}

// Add nearby user markers to map
function addNearbyUserMarkers(users, alertLat, alertLon) {
    // Clear existing nearby user markers
    nearbyUserMarkers.forEach(marker => map.removeLayer(marker));
    nearbyUserMarkers = [];

    users.forEach(user => {
        if (user.last_latitude && user.last_longitude) {
            const marker = L.marker([user.last_latitude, user.last_longitude], {
                icon: createUserIcon()
            }).addTo(map);

            // Create popup content
            const popupContent = `
                <div style="min-width: 180px;">
                    <strong style="color: #3b82f6; font-size: 14px;">Nearby Helper</strong><br>
                    <strong>Name:</strong> ${user.name || 'Unknown'}<br>
                    <strong>Phone:</strong> ${user.phone || 'N/A'}<br>
                    <strong>Distance:</strong> ${user.distance.toFixed(2)} km<br>
                    <strong>Last Update:</strong> ${formatTimeAgo(user.last_location_updated_at)}
                </div>
            `;

            marker.bindPopup(popupContent);

            // Show popup on hover
            marker.on('mouseover', function (e) {
                this.openPopup();
            });

            nearbyUserMarkers.push(marker);
        }
    });

    // Draw circle showing 5km radius
    if (alertLat && alertLon) {
        const circle = L.circle([alertLat, alertLon], {
            color: '#fbbf24',
            fillColor: '#fbbf24',
            fillOpacity: 0.1,
            radius: 5000 // 5km in meters
        }).addTo(map);

        nearbyUserMarkers.push(circle);

        // Fit map to show alert and radius
        map.fitBounds(circle.getBounds());
    }

    console.log(`Added ${users.length} nearby user markers`);
}

// Clear nearby user markers
function clearNearbyUserMarkers() {
    nearbyUserMarkers.forEach(marker => map.removeLayer(marker));
    nearbyUserMarkers = [];
}
