import java.util.Collection
import java.util.Iterator

import org.hyperic.hq.authz.server.session.Resource
import org.hyperic.hq.authz.server.session.ResourceEdge
import org.hyperic.hq.authz.server.session.ResourceEdgeDAO
import org.hyperic.hq.authz.server.session.ResourceRelation
import org.hyperic.hq.authz.server.session.ResourceManagerEJBImpl
import org.hyperic.hq.authz.shared.AuthzConstants
import org.json.JSONArray
import org.json.JSONObject

import org.hyperic.hq.hqu.rendit.BaseController
import org.hyperic.hq.measurement.MeasurementConstants

class VsphereController extends BaseController {
    protected void init() {
        onlyAllowSuperUsers()
    }

    private getNode(id, name, classes, collapsed, children) {
        JSONObject node = new JSONObject();
        
        node.put("id", id)
        node.put("text", name)
        
        classes && node.put("classes", classes)
        
        
        if (children) {
            node.put("children", children)
            
            collapsed && node.put("collapsed", true)
        }
        
        node
    }
    
    private getAvailability(resource) {
        def availMetric = resource.getAvailabilityMeasurement()

        if (availMetric) {
            def mv = availMetric.getLastDataPoint()
            
            return mv.value
        }
        
        return 0
    }

    /**
     * Generate a 'VMware vSphere VM' node.
     */
    private getVMNode(vm) {
        def avail = getAvailability(vm)
        def icon
        
        if (avail == MeasurementConstants.AVAIL_UP) {
            icon = "icon icon-vmOn"
        } else if (avail == MeasurementConstants.AVAIL_DOWN) {
            icon = "icon icon-vmOff"
        } else if (avail == MeasurementConstants.AVAIL_PAUSED) {
            icon = "icon icon-vmSuspended"
        } else {
            icon = "icon icon-vm"
        }

        // Check if this VM has any associated servers.
        def descendants = resourceHelper.getDescendantResourceCountByVirtualRelation(vm)
        def children = null
        
        if (descendants > 0) { 
            children = new JSONArray()
        }
        
        getNode(vm.id, vm.name, icon, true, children)
    }
    
    private getChildNodes(id) {
        def results = new JSONArray()
        def nodes = []
                     
        if (id) {
            def resource = resourceHelper.findById(id)

            if (resource.prototype.name == 'VMware vSphere VM') {
                def descendants = resourceHelper.findDescendantResourcesByVirtualRelation(resource)
                
                descendants.each { res ->
                    if (res.resourceType.id == AuthzConstants.authzServer) {
                        nodes << res
                    }
                }
            } else {
                nodes = resourceHelper.findChildResourcesByVirtualRelation(resource)
            }
        } else {
            nodes = resourceHelper.find(byPrototype: 'VMware vCenter')
        }
        
        nodes.sort({ a, b -> a.name.toLowerCase() <=> b.name.toLowerCase() }).each { node ->
            if (node.prototype.name == 'VMware vCenter') {
                results.put(getNode(node.id, node.name, "icon icon-vcenter", true, new JSONArray()))
            } else if (node.prototype.name == 'VMware vSphere Host') {
                results.put(getNode(node.id, node.name, "icon icon-host", true, new JSONArray()))
            } else if (node.prototype.name == 'VMware vSphere VM') {
                results.put(getVMNode(node))
            } else {
                results.put(getNode(node.id, node.name, "icon icon-hq", null, null))
            }
        }
        
        results
    }
    
    private getAncestors(id) {
        def resource = resourceHelper.findById(id)
        def openNodes = []
                         
        if (resource) {
            openNodes = resourceHelper.findAncestorsByVirtualRelation(resource).collect { res -> res.id }
        }
        
        openNodes
    }
    
    private generateBranch(nodes, openNodes) {
        for (int x = 0; x < nodes.length(); x++) {
            def node = nodes.getJSONObject(x)
            def id = node.get("id")
            
            if (openNodes.find { it == id }) {
                def children = getChildNodes(id)
                
                if (children.length() > 0) {
                    generateBranch(children, openNodes)
                    
                    node.put("collapsed", false)
                }
                
                node.put("children", children)
            }
        }
    }
    
    def findByName(params) {
        def resourceName = params.getOne('name')
        def result = new JSONArray()
        
        if (resourceName) {
            def resources = resourceHelper.findResourcesByNameAndVirtualRelation(resourceName)

            resources.sort({ a, b -> a.name.toLowerCase() <=> b.name.toLowerCase() }).each { resource ->
                def listItem = new JSONObject()
                
                listItem.put("id", resource.id)
                listItem.put("value", resource.name)
                
                result.put(listItem)
            }
        }
        
        render(inline:"${result}", contentType:'text/json-comment-filtered')
    }
    
    def inventory(params) {
        def nodeId = params.getOne('nodeId')?.toInteger()
        def nodes = new JSONArray()
        def result = new JSONObject()
        
        if (nodeId) {
            nodes = getChildNodes(nodeId)
        } else {
            def openNodes = []  
            def selectedId = params.getOne('sn')?.toInteger()
            
            for (String id in params.get('on[]')) {
                openNodes << Integer.valueOf(id)
            }
            
            if (selectedId) {
                def resource = resourceHelper.findById(selectedId)
                
                if (resource.resourceType.id == AuthzConstants.authzPlatform &&
                    resource.prototype.name != 'VMware vSphere Host' &&
                    resource.prototype.name != 'VMware vSphere VM') {
                    // Get the associated/parent vm since we don't show the actual HQ platform in this view
                    def parent = resourceHelper.getParentResourceByVirtualRelation(resource)
                    
                    selectedId = parent.id
                }
                 
                openNodes = (openNodes + getAncestors(selectedId)).unique()
                
                result.put("selectedId", selectedId)
            }
           
            nodes = getChildNodes(null)
            
            if (nodes.length() > 0 && openNodes.size() > 0) {
                generateBranch(nodes, openNodes)
            } 
        }

        result.put("payload", nodes)

        render(inline:"${result}", contentType:'text/json-comment-filtered')
    }

    def index(params) {
        def openNodes = []
        def selectedId = params.getOne('sn')?.toInteger()
        def result = [:]
                   
        if (selectedId) {
            def resource = resourceHelper.findById(selectedId)
            
            if (resource.resourceType.id == AuthzConstants.authzPlatform &&
                resource.prototype.name != 'VMware vSphere Host' &&
                resource.prototype.name != 'VMware vSphere VM') {
                // Get the associated/parent vm since we don't show the actual HQ platform in this view
                def parent = resourceHelper.getParentResourceByVirtualRelation(resource)
                
                selectedId = parent.id
            }
            
            openNodes = getAncestors(selectedId)
        }
        
        def nodes = getChildNodes(null)
                    
        if (nodes.length() > 0 && openNodes.size() > 0) {
            generateBranch(nodes, openNodes)
        } 

        render(locals: [payload: nodes, selectedId: selectedId])
    }
}