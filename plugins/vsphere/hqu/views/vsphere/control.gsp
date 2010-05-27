<div class="panelHeader">
	<span class="navLink">
		<% if (associatedPlatform) { %>
			${linkTo(l.getFormattedMessage("link.goto.inventory.page", associatedPlatform.name), [resource:associatedPlatform])}
		<% } else if (resource) { %>
			${linkTo(l.getFormattedMessage("link.goto.inventory.page", resource.name), [resource:resource])}
		<% } %>
	</span>
</div>
<% if (resource) { %>
	<div class="vm-action-container">
		<h2>${l.getFormattedMessage('text.vm.commands', resource.name)}</h2>
		<ul class="vm-action-list">
		<% for (action in actions) { %>
			<li class="vm-action vm-action-${action.toLowerCase()}">
				<a href="#" onclick="runControlAction(${resource.id}, '${action}', ''); return false;">${l['text.control.' + action.toLowerCase()]}</a>			
			</li>
		<% } %>
		</ul>
		<div id="controlStatus" style="display:none;">
			<p>
				<span id="actionName"></span><br/>
				<span id="actionDescription"></span>
			</p>
			<p>
				<span id="actionStatus"></span><br/><br/>
				<span id="actionMessage"></span>
			</p>
		</div>
  </div>
<% } else { %>
<div>${l['text.no.actions.available']}</div>
<% } %>
<script>
	function updateControlStatus(data) {
		if (data) {
			var div = jQuery("#controlStatus");
			var className = "action-inprogress";
			
			if (data.status == 'Failed') {
				className = "action-failed";
			} else if (data.status == 'Completed') {
				className = "action-completed";
			}
	
			div.find("#actionName").text(data.name);
			div.find("#actionDescription").text(data.description);
			div.find("#actionStatus").removeClass().addClass(className).text(data.status);
			div.find("#actionMessage").text(data.message);
			div.show();
		}
	};
	
	function checkControlActionStatus(resourceId, actionId) {
		jQuery.ajax({
			url: '/hqu/vsphere/vsphere/checkControlStatus.hqu',
			data: {
				'id': resourceId,
				'aid': actionId
			},
			success: function(data) {
				var payload = data.payload;
				
				if (payload) {
					if (payload.status.toLowerCase() == 'in progress') {
						jQuery("#controlStatus").oneTime("3s", function() {
							checkControlActionStatus(resourceId, actionId);
						});
					} else {
						updateControlStatus(data);
					}
				}
			}
		});
	}
	
	function runControlAction(id, action, args) {
		var params = {
			'id': id,
			'action': action,
			'args': args
		};
		
		jQuery.ajax({
			url: '/hqu/vsphere/vsphere/executeControlAction.hqu',
			data: params,
			success: function(data) {
				if (data.success) {
					updateControlStatus(data.payload);
					
					if (data.status.toLowerCase() == 'in progress') {
						jQuery("#controlStatus").oneTime("3s", function() {
							checkControlActionStatus(id, data.payload.id);
						});
					}
				} else {
					alert(data.error);			
				}
			}
		});
	};
</script>