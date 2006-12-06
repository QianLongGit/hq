<%@ page language="java" %>
<%@ page errorPage="/common/Error.jsp" %>
<%@ taglib uri="struts-html-el" prefix="html" %>
<%@ taglib uri="struts-tiles" prefix="tiles" %>
<%@ taglib uri="jstl-fmt" prefix="fmt" %>
<%@ taglib uri="jstl-c" prefix="c" %>
<%@ taglib uri="hq" prefix="hq" %>
<%--
  NOTE: This copyright does *not* cover user programs that use HQ
  program services by normal system calls through the application
  program interfaces provided as part of the Hyperic Plug-in Development
  Kit or the Hyperic Client Development Kit - this is merely considered
  normal use of the program, and does *not* fall under the heading of
  "derived work".
  
  Copyright (C) [2004, 2005, 2006], Hyperic, Inc.
  This file is part of HQ.
  
  HQ is free software; you can redistribute it and/or modify
  it under the terms version 2 of the GNU General Public License as
  published by the Free Software Foundation. This program is distributed
  in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
  even the implied warranty of MERCHANTABILITY or FITNESS FOR A
  PARTICULAR PURPOSE. See the GNU General Public License for more
  details.
  
  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307
  USA.
 --%>

<html:link page="/ResourceHub.do?ff=" linkName="browseUrl" styleId="browseUrl" style="visibility:hidden;"></html:link>

<script type="text/javascript">
function requestAvailSummary() {
var availResourcesUrl = "<html:rewrite page="/dashboard/ViewAvailSummary.do"/>"
	new Ajax.Request(availResourcesUrl, {method: 'get', onSuccess:showAvailSummary, onFailure :reportError});
	}
onloads.push(requestAvailSummary);
Ajax.Responders.register({
	onCreate: function() {
	       if($('loading') && Ajax.activeRequestCount > 0)
	               Effect.Appear('loading',{duration: 0.50, queue: 'end'});
	},
	onComplete: function() {
	       if($('loading') && Ajax.activeRequestCount == 0)
	               Effect.Fade('loading',{duration: 0.2, queue: 'end'});
	}
});
</script>
<!-- JSON available at /dashboard/ViewAvailSummary.do -->
 
<div class="effectsPortlet">
<tiles:insert definition=".header.tab">
  <tiles:put name="tabKey" value="dash.home.AvailSummary"/>
  <tiles:put name="adminUrl" beanName="adminUrl" />
  <tiles:put name="portletName" beanName="portletName" />
  <tiles:put name="dragDrop" value="true"/>
</tiles:insert>
   <!-- Content Block  -->
    <table class="table" width="100%" border="0" cellspacing="0" cellpadding="0" id="availTable">
      <tbody>
        <tr class="tableRowHeader">
          <th width="90%" class="tableRowInactive">Resource Type</th>
          <th width="10%" align="center" class="tableRowInactive"><span style="color:red">*</span> / *</th>
        </tr>
        <!-- table rows are inserted here dynamically -->
      </tbody>
    </table>
    <table width="100%" cellpadding="0" cellspacing="0" border="0" id="noAvailSummary" style="display:none;">
      <tr class="ListRow">
        <td class="ListCell"></td>
      </tr>
    </table>
  </div>

