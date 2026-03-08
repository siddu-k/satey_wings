// Main Application State
let currentAlerts = [];
let activeAlertId = null;
let realtimeSubscription = null;

// Initialize the app
async function initApp() {
    console.log('SATEY Web Dashboard initializing...');

    // Initialize map
    initMap();

    // Set up event listeners
    setupEventListeners();

    // Initial data load
    await refreshDashboard();

    // Subscribe to real-time updates
    realtimeSubscription = subscribeToAlerts(handleRealtimeUpdate);

    // Set up auto-cleanup older than 1 hour every minute
    setInterval(performCleanup, 60000);
}

// Setup Event Listeners
function setupEventListeners() {
    // Status Filter
    document.getElementById('statusFilter').addEventListener('change', (e) => {
        refreshDashboard(e.target.value);
    });

    // Reset Filter
    document.getElementById('resetFilter').addEventListener('click', () => {
        document.getElementById('statusFilter').value = 'all';
        refreshDashboard('all');
    });

    // Update Button
    document.getElementById('updateBtn').addEventListener('click', () => {
        refreshDashboard(document.getElementById('statusFilter').value);
    });

    // Close Popup
    document.getElementById('closePopup').addEventListener('click', hideAlertDetails);

    // Hide Nearby Section
    document.getElementById('hideNearby').addEventListener('click', () => {
        document.getElementById('nearbySection').style.display = 'none';
        document.getElementById('alertsList').style.display = 'block';
        clearNearbyUserMarkers();
    });
}

// Handle Real-time Update
function handleRealtimeUpdate(payload) {
    const { eventType, new: newRecord, old: oldRecord } = payload;

    if (eventType === 'INSERT') {
        // Only add if fresh
        const oneHourAgo = new Date();
        oneHourAgo.setHours(oneHourAgo.getHours() - 1);

        if (new Date(newRecord.created_at) >= oneHourAgo) {
            // Remove any existing alert from the same user to deduplicate
            currentAlerts = currentAlerts.filter(alert => alert.user_id !== newRecord.user_id);
            currentAlerts.unshift(newRecord);
            updateUI();
        }
    } else if (eventType === 'UPDATE') {
        currentAlerts = currentAlerts.map(alert =>
            alert.id === newRecord.id ? newRecord : alert
        );
        updateUI();

        // If the updated alert is the one currently shown, update the details popup
        if (activeAlertId === newRecord.id) {
            updateDetailsPopup(newRecord);
        }
    } else if (eventType === 'DELETE') {
        currentAlerts = currentAlerts.filter(alert => alert.id !== oldRecord.id);
        updateUI();

        if (activeAlertId === oldRecord.id) {
            hideAlertDetails();
        }
    }
}

// Perform cleanup of alerts older than 1 hour
function performCleanup() {
    console.log('Performing cleanup of old alerts...');
    const oneHourAgo = new Date();
    oneHourAgo.setHours(oneHourAgo.getHours() - 1);

    const beforeCount = currentAlerts.length;
    currentAlerts = currentAlerts.filter(alert => new Date(alert.created_at) >= oneHourAgo);

    if (currentAlerts.length !== beforeCount) {
        console.log(`Cleaned up ${beforeCount - currentAlerts.length} stale alerts`);
        updateUI();
    }
}

// Refresh Dashboard Data from DB
async function refreshDashboard(statusFilter = 'all') {
    const filter = statusFilter || document.getElementById('statusFilter').value;
    currentAlerts = await fetchAlerts(filter);
    updateUI();
}

// Update the UI (Stats, Markers, List)
function updateUI() {
    updateStats(currentAlerts);
    renderAlertsList(currentAlerts);
    addAlertMarkers(currentAlerts);
}

// Update Statistics Cards
function updateStats(alerts) {
    document.getElementById('totalAlerts').textContent = alerts.length;

    const activeCount = alerts.filter(a => a.status === 'sent' || a.status === 'active').length;
    document.getElementById('activeAlerts').textContent = activeCount;
}

// Render Alerts List in Sidebar
function renderAlertsList(alerts) {
    const listContainer = document.getElementById('alertsList');
    listContainer.innerHTML = '';

    if (alerts.length === 0) {
        listContainer.innerHTML = '<div style="text-align: center; color: #888; padding: 20px;">No alerts in last 1 hour</div>';
        return;
    }

    alerts.forEach(alert => {
        const item = document.createElement('div');
        item.className = 'alert-item';
        if (alert.id === activeAlertId) item.classList.add('active');

        const timeAgo = formatTimeAgo(alert.created_at);

        item.innerHTML = `
            <div class="alert-header">
                <span class="alert-name">${alert.user_name || 'Unknown User'}</span>
                <span class="alert-badge">${alert.status || 'sent'}</span>
            </div>
            <div class="alert-time">${timeAgo}</div>
            <div class="alert-details">
                ${alert.alert_type || 'Voice Help'} • ${alert.user_phone || 'No phone'}
            </div>
            <button class="find-help-btn" onclick="handleFindHelp(event, '${alert.id}')">Find Nearby Help</button>
        `;

        item.addEventListener('click', () => showAlertDetails(alert.id));
        listContainer.appendChild(item);
    });
}

// Show Alert Details in Popup
async function showAlertDetails(alertId) {
    const alert = currentAlerts.find(a => a.id === alertId);
    if (!alert) return;

    activeAlertId = alertId;
    updateDetailsPopup(alert);

    // Recenter map on this alert
    if (alert.latitude && alert.longitude) {
        map.setView([alert.latitude, alert.longitude], 15);
    }
}

// Update details popup content
function updateDetailsPopup(alert) {
    document.getElementById('popupName').textContent = alert.user_name || 'N/A';
    document.getElementById('popupPhone').textContent = alert.user_phone || 'N/A';
    document.getElementById('popupEmail').textContent = alert.user_email || 'N/A';
    document.getElementById('popupType').textContent = alert.alert_type || 'Voice Help';

    const statusBadge = document.getElementById('popupStatus');
    statusBadge.textContent = alert.status || 'SENT';
    statusBadge.className = `status-badge ${alert.status?.toLowerCase() || 'sent'}`;

    document.getElementById('popupTime').textContent = formatDateTime(alert.created_at);

    // Set photos
    const frontPhoto = document.getElementById('frontPhoto');
    const backPhoto = document.getElementById('backPhoto');

    frontPhoto.src = alert.front_photo_url || 'https://via.placeholder.com/150?text=No+Photo';
    backPhoto.src = alert.back_photo_url || 'https://via.placeholder.com/150?text=No+Photo';

    // Ensure photos container is visible if there are photos
    document.getElementById('popupPhotos').style.display = (alert.front_photo_url || alert.back_photo_url) ? 'block' : 'none';

    // Show the popup
    document.getElementById('alertPopup').style.display = 'block';
}

// Hide Alert Details
function hideAlertDetails() {
    document.getElementById('alertPopup').style.display = 'none';
    activeAlertId = null;
    clearNearbyUserMarkers();

    // Also hide nearby section if open
    document.getElementById('nearbySection').style.display = 'none';
    document.getElementById('alertsList').style.display = 'block';
}

// Handle Find Help Button Click
async function handleFindHelp(event, alertId) {
    event.stopPropagation(); // Prevent triggering the alert detail click

    const alert = currentAlerts.find(a => a.id === alertId);
    if (!alert || !alert.latitude || !alert.longitude) {
        alert('Cannot find location for this alert');
        return;
    }

    console.log(`Finding help for ${alert.user_name} at ${alert.latitude}, ${alert.longitude}`);

    // Show alert details popup
    showAlertDetails(alertId);

    // Find nearby users
    const nearbyUsers = await findNearbyUsers(alert.latitude, alert.longitude, 5, alert.user_id); // 5km radius, exclude initiator

    // Show nearby section in sidebar
    const nearbySection = document.getElementById('nearbySection');
    const alertsList = document.getElementById('alertsList');
    const nearbyList = document.getElementById('nearbyList');

    nearbySection.style.display = 'block';
    alertsList.style.display = 'none';
    nearbyList.innerHTML = '';

    if (nearbyUsers.length === 0) {
        nearbyList.innerHTML = '<div style="text-align: center; color: #888; padding: 10px;">No users found within 5km</div>';
        clearNearbyUserMarkers();
    } else {
        // Add markers for nearby users
        addNearbyUserMarkers(nearbyUsers, alert.latitude, alert.longitude);

        // Render nearby users in sidebar
        nearbyUsers.forEach(user => {
            const item = document.createElement('div');
            item.className = 'nearby-item';
            item.innerHTML = `
                <div class="nearby-item-header">
                    <span>${user.name}</span>
                    <span class="nearby-item-dist">${user.distance.toFixed(2)} km</span>
                </div>
                <div>${user.phone || 'No phone'}</div>
                <div class="nearby-item-time">Last seen: ${formatTimeAgo(user.last_location_updated_at)}</div>
            `;
            nearbyList.appendChild(item);
        });
    }
}

// Initialize on load
window.addEventListener('load', initApp);
