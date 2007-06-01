import org.hyperic.hq.ui.rendit.BaseController
import org.hyperic.hq.hqu.server.session.UIPluginManagerEJBImpl

class ManageController 
	extends BaseController
{
    def ManageController() {
        setTemplate('standard')  // in views/templates/standard.gsp 
    }
    
    private def getPMan() {
        UIPluginManagerEJBImpl.one
    }
    
    def index = { params ->
    	render(locals:[plugins : pMan.findAll()])
    }
    
    def deletePlugin = { params ->
	    def plugin = pMan.findPluginById(new Integer(params.getOne('id')))
	    pMan.deletePlugin(plugin)
    	redirectTo(action : 'index')
    }
    
    def showPlugin = { params ->
        def plugin = pMan.findPluginById(new Integer(params.getOne('id')))
        render(locals:[plugin : plugin])
    }
    
    def attach = { params ->
		def view   = pMan.findViewById(new Integer(params.getOne('id')))
		redirectTo(action : 'showPlugin', id : view.plugin)
    }
}
