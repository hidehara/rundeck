/*
 * Copyright 2016 SimplifyOps, Inc. (http://simplifyops.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package rundeck.controllers

import com.dtolabs.client.utils.Constants
import com.dtolabs.rundeck.app.api.jobs.info.JobInfo
import com.dtolabs.rundeck.app.api.jobs.info.JobInfoList
import com.dtolabs.rundeck.app.support.BaseQuery
import com.dtolabs.rundeck.app.support.QueueQuery
import com.dtolabs.rundeck.app.support.ScheduledExecutionQuery
import com.dtolabs.rundeck.app.support.StoreFilterCommand
import com.dtolabs.rundeck.core.authorization.AuthContext
import com.dtolabs.rundeck.core.authorization.UserAndRolesAuthContext
import com.dtolabs.rundeck.core.authorization.Validation
import com.dtolabs.rundeck.core.common.Framework
import com.dtolabs.rundeck.core.common.IRundeckProject
import com.dtolabs.rundeck.core.execution.service.FileCopierService
import com.dtolabs.rundeck.core.execution.service.NodeExecutorService
import com.dtolabs.rundeck.core.plugins.configuration.PropertyScope
import com.dtolabs.rundeck.core.plugins.configuration.Validator
import com.dtolabs.rundeck.plugins.file.FileUploadPlugin
import com.dtolabs.rundeck.plugins.scm.ScmPluginException
import com.dtolabs.rundeck.plugins.storage.StorageConverterPlugin
import com.dtolabs.rundeck.plugins.storage.StoragePlugin
import com.dtolabs.rundeck.server.authorization.AuthConstants
import com.dtolabs.rundeck.server.plugins.services.StorageConverterPluginProviderService
import com.dtolabs.rundeck.server.plugins.services.StoragePluginProviderService
import grails.converters.JSON
import groovy.xml.MarkupBuilder
import org.grails.plugins.metricsweb.MetricService
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import rundeck.Execution
import rundeck.LogFileStorageRequest
import rundeck.ScheduledExecution
import rundeck.ScheduledExecutionFilter
import rundeck.User
import rundeck.codecs.JobsXMLCodec
import rundeck.codecs.JobsYAMLCodec
import rundeck.filters.ApiRequestFilters
import rundeck.services.ApiService
import rundeck.services.AuthorizationService
import rundeck.services.ExecutionService
import rundeck.services.FrameworkService
import rundeck.services.LogFileStorageService
import rundeck.services.LoggingService
import rundeck.services.NotificationService
import rundeck.services.PluginService
import rundeck.services.ScheduledExecutionService
import rundeck.services.ScmService
import rundeck.services.UserService
import rundeck.services.framework.RundeckProjectConfigurable

import javax.servlet.http.HttpServletResponse
import java.lang.management.ManagementFactory

class MenuController extends ControllerBase implements ApplicationContextAware{
    FrameworkService frameworkService
    MenuService menuService
    ExecutionService executionService
    UserService userService
    ScheduledExecutionService scheduledExecutionService
    NotificationService notificationService
    LoggingService LoggingService
    LogFileStorageService logFileStorageService
    StoragePluginProviderService storagePluginProviderService
    StorageConverterPluginProviderService storageConverterPluginProviderService
    PluginService pluginService
    MetricService metricService

    def configurationService
    ScmService scmService
    def quartzScheduler
    def ApiService apiService
    def AuthorizationService authorizationService
    def ApplicationContext applicationContext
    static allowedMethods = [
            deleteJobfilter:'POST',
            storeJobfilter:'POST',
            apiJobDetail:'GET',
            apiResumeIncompleteLogstorage:'POST',
            cleanupIncompleteLogStorageAjax:'POST',
            resumeIncompleteLogStorageAjax: 'POST',
            resumeIncompleteLogStorage: 'POST',
            cleanupIncompleteLogStorage: 'POST',
    ]
    def list = {
        def results = index(params)
        render(view:"index",model:results)
    }

    def nowrunning={ QueueQuery query->
        //find currently running executions
        
        if(params['Clear']){
            query=null
        }
        if(null!=query && !params.find{ it.key.endsWith('Filter')
        }){
            query.recentFilter="1h"
            params.recentFilter="1h"
        }
        if(!query.projFilter && params.project){
            query.projFilter=params.project
        }
        if(null!=query){
            query.configureFilter()
        }
        
        //find previous executions
        def model = metricService?.withTimer(MenuController.name, actionName+'.queryQueue') {
            executionService.queryQueue(query)
        } ?: executionService.queryQueue(query)
        //        System.err.println("nowrunning: "+model.nowrunning);
        model = executionService.finishQueueQuery(query,params,model)

        //include id of last completed execution for the project
        def eid=executionService.lastExecutionId(query.projFilter)
        model.lastExecId=eid
        
        User u = userService.findOrCreateUser(session.user)
        Map filterpref=[:] 
        if(u){
            filterpref= userService.parseKeyValuePref(u.filterPref)
        }
        model.boxfilters=filterpref
        return model
    }
    def queueList={QueueQuery query->
        def model= executionService.queryQueue(query)
        model = executionService.finishQueueQuery(query,params,model)
        response.setHeader(Constants.X_RUNDECK_RESULT_HEADER,"Jobs found: ${model.nowrunning.size()}")
        render(contentType:"text/xml",encoding:"UTF-8"){
            result{
                items(count:model.nowrunning.size()){
                    model.nowrunning.each{ Execution job ->
                        delegate.'item'{
                            id(job.id.toString())
                            name(job.toString())
                            url(g.createLink(controller:'execution',action:'follow',id:job.id))
                        }
                    }
                }
            }
        }
    }
    
    def nowrunningFragment = {QueueQuery query->
        if('true'!=request.getHeader('x-rundeck-ajax')) {
            return redirect(action: 'index',controller: 'reports',params: params)
        }
        def results = nowrunning(query)
        return results
    }
    def nowrunningAjax = {QueueQuery query->
        if('true'!=request.getHeader('x-rundeck-ajax')) {
            return redirect(action: 'index', controller: 'reports', params: params)
        }
        def results = nowrunning(query)
        //structure dataset for client-side event status processing
        def running= results.nowrunning.collect {
            def map = it.toMap()
            def data = [
                    status: ExecutionService.getExecutionState(it),
                    executionHref: createLink(controller: 'execution', action: 'show', absolute: true, id: it.id),
                    executionId: it.id,
                    duration: (it.dateCompleted?:new Date()).time - it.dateStarted.time
            ]
            if(!it.scheduledExecution){
                data['executionString']=map.workflow.commands[0].exec
            }else{
                data['jobName']=it.scheduledExecution.jobName
                data['jobGroup']=it.scheduledExecution.groupPath
                data['jobId']=it.scheduledExecution.extid
                data['jobPermalink']= createLink(
                        controller: 'scheduledExecution',
                        action: 'show',
                        absolute: true,
                        id: it.scheduledExecution.extid,
                        params:[project:it.scheduledExecution.project]
                )
                if (it.scheduledExecution && it.scheduledExecution.totalTime >= 0 && it.scheduledExecution.execCount > 0) {
                    data['jobAverageDuration']= Math.floor(it.scheduledExecution.totalTime / it.scheduledExecution.execCount)
                }
                if (it.argString) {
                    data.jobArguments = FrameworkService.parseOptsFromString(it.argString)
                }
            }
            map + data
        }
        render( ([nowrunning:running] + results.subMap(['total','max','offset'])) as JSON)
    }
    def queueFragment = {QueueQuery query->
        def results = nowrunning(query)
        return results
    }


    def index() {
        /**
         * redirect to configured start page, or default page
         */

        if (!params.project) {
            return redirect(controller: 'menu', action: 'home')
        }
        def startpage = params.page?: grailsApplication.config.rundeck.gui.startpage ?: 'jobs'
        switch (startpage){
            case 'home':
                return redirect(controller: 'menu', action: 'home')
            case 'projectHome':
                return redirect(controller: 'menu', action: 'projectHome', params: [project: params.project])
            case 'nodes':
                return redirect(controller:'framework',action:'nodes',params:[project:params.project])
            case 'run':
            case 'adhoc':
                return redirect(controller:'framework',action:'adhoc',params:[project:params.project])
            case 'jobs':
                return redirect(controller:'menu',action:'jobs', params: [project: params.project])
            case 'createJob':
                return redirect(controller:'scheduledExecution',action: 'create', params: [project: params.project])
            case 'uploadJob':
                return redirect(controller: 'scheduledExecution', action: 'upload', params: [project: params.project])
            case 'configure':
                return redirect(controller: 'framework', action: 'editProject', params: [project: params.project])
            case 'history':
            case 'activity':
            case 'events':
                return redirect(controller:'reports',action:'index', params: [project: params.project])
        }
        return redirect(controller:'framework',action:'nodes', params: [project: params.project])
    }
    
    def clearJobsFilter = { ScheduledExecutionQuery query ->
        return redirect(action: 'jobs', params: [project: params.project])
    }
    def jobs (ScheduledExecutionQuery query ){
        
        def User u = userService.findOrCreateUser(session.user)
        if(params.size()<1 && !params.filterName && u ){
            Map filterpref = userService.parseKeyValuePref(u.filterPref)
            if(filterpref['workflows']){
                params.filterName=filterpref['workflows']
            }
        }
        if(!params.project){
            return redirect(controller: 'menu',action: 'home')
        }
        
        def results = jobsFragment(query)
        results.execQueryParams=query.asExecQueryParams()
        results.reportQueryParams=query.asReportQueryParams()
        if(results.warning){
            request.warn=results.warning
        }

        def framework = frameworkService.getRundeckFramework()
        def rdprojectconfig = framework.projectManager.loadProjectConfig(params.project)
        results.jobExpandLevel = scheduledExecutionService.getJobExpandLevel(rdprojectconfig)
        AuthContext authContext = frameworkService.getAuthContextForSubjectAndProject(
                session.subject,
                params.project
        )
        def projectNames = frameworkService.projectNames(authContext)
        def authProjectsToCreate = []
        projectNames.each{
            if(it != params.project && frameworkService.authorizeProjectResource(
                    authContext,
                    AuthConstants.RESOURCE_TYPE_JOB,
                    AuthConstants.ACTION_CREATE,
                    it
            )){
                authProjectsToCreate.add(it)
            }
        }
        results.projectNames = authProjectsToCreate
        withFormat{
            html {
                results
            }
            yaml{
                final def encoded = JobsYAMLCodec.encode(results.nextScheduled as List)
                render(text:encoded,contentType:"text/yaml",encoding:"UTF-8")
            }
            xml{
                response.setHeader(Constants.X_RUNDECK_RESULT_HEADER,"Jobs found: ${results.nextScheduled?.size()}")
                def writer = new StringWriter()
                def xml = new MarkupBuilder(writer)
                JobsXMLCodec.encodeWithBuilder(results.nextScheduled,xml)
                writer.flush()
                render(text:writer.toString(),contentType:"text/xml",encoding:"UTF-8")
            }
        }
    }
    /**
     *
     * @param query
     * @return
     */
    def jobsAjax(ScheduledExecutionQuery query){
        if('true'!=request.getHeader('x-rundeck-ajax')) {
            return redirect(action: 'jobs', controller: 'menu', params: params)
        }
        if(!params.project){
            return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_BAD_REQUEST,
                                                        code: 'api.error.parameter.required', args: ['project']])
        }
        query.projFilter = params.project
        //test valid project

        def exists=frameworkService.existsFrameworkProject(params.project)
        if(!exists){
            return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_NOT_FOUND,
                                                        code: 'api.error.item.doesnotexist', args: ['project',params.project]])
        }
        if(query.hasErrors()){
            return apiService.renderErrorFormat(response,
                                                [
                                                        status: HttpServletResponse.SC_BAD_REQUEST,
                                                        code: "api.error.parameter.error",
                                                        args: [query.errors.allErrors.collect { message(error: it) }.join("; ")]
                                                ])
        }
        //don't load scm status for api response
        params['_no_scm']=true

        def results = jobsFragment(query)

        def clusterModeEnabled = frameworkService.isClusterModeEnabled()
        def serverNodeUUID = frameworkService.serverUUID
        def data = new JobInfoList(
                results.nextScheduled.collect { ScheduledExecution se ->
                    Map data = [:]
                    if (clusterModeEnabled) {
                        data = [
                                serverNodeUUID: se.serverNodeUUID,
                                serverOwner   : se.serverNodeUUID == serverNodeUUID
                        ]
                    }
                    if(results.nextExecutions?.get(se.id)){
                        data.nextScheduledExecution=results.nextExecutions?.get(se.id)
                    }
                    if (se.totalTime >= 0 && se.execCount > 0) {
                        def long avg = Math.floor(se.totalTime / se.execCount)
                        data.averageDuration = avg
                    }
                    JobInfo.from(
                            se,
                            apiService.apiHrefForJob(se),
                            apiService.guiHrefForJob(se),
                            data
                    )
                }
        )
        respond(
                data,
                [formats: [ 'json']]
        )
    }

    def jobsFragment(ScheduledExecutionQuery query) {
        long start=System.currentTimeMillis()
        UserAndRolesAuthContext authContext
        def usedFilter=null
        
        if(params.filterName){
            //load a named filter and create a query from it
            def User u = userService.findOrCreateUser(session.user)
            if(u){
                ScheduledExecutionFilter filter = ScheduledExecutionFilter.findByNameAndUser(params.filterName,u)
                if(filter){
                    def query2 = filter.createQuery()
                    query2.setPagination(query)
                    query=query2
                    def props=query.properties
                    params.putAll(props)
                    usedFilter=params.filterName
                }
            }
        }
        if(params['Clear']){
            query=new ScheduledExecutionQuery()
            usedFilter=null
        }
        if(query && !query.projFilter && params.project) {
            query.projFilter = params.project
        }
        if(query && query.projFilter){
            authContext = frameworkService.getAuthContextForSubjectAndProject(session.subject, params.project)
        } else {
            authContext = frameworkService.getAuthContextForSubject(session.subject)
        }
        def results=listWorkflows(query,authContext,session.user)
        //fill scm status
        if(params['_no_scm']!=true) {
            if (frameworkService.authorizeApplicationResourceAny(authContext,
                                                                 frameworkService.authResourceForProject(
                                                                         params.project
                                                                 ),
                                                                 [AuthConstants.ACTION_ADMIN, AuthConstants.ACTION_EXPORT]
            )) {
                def pluginData = [:]
                try {
                    if (scmService.projectHasConfiguredExportPlugin(params.project)) {
                        pluginData.scmExportEnabled = true
                        pluginData.scmStatus = scmService.exportStatusForJobs(results.nextScheduled)
                        pluginData.scmExportStatus = scmService.exportPluginStatus(authContext, params.project)
                        pluginData.scmExportActions = scmService.exportPluginActions(authContext, params.project)
                        pluginData.scmExportRenamed = scmService.getRenamedJobPathsForProject(params.project)
                        results.putAll(pluginData)
                    }
                } catch (ScmPluginException e) {
                    results.warning = "Failed to update SCM Export status: ${e.message}"
                }
            }
            if (frameworkService.authorizeApplicationResourceAny(authContext,
                                                                 frameworkService.authResourceForProject(
                                                                         params.project
                                                                 ),
                                                                 [AuthConstants.ACTION_ADMIN, AuthConstants.ACTION_IMPORT]
            )) {

                def pluginData = [:]
                try {
                    if (scmService.projectHasConfiguredImportPlugin(params.project)) {
                        pluginData.scmImportEnabled = true
                        pluginData.scmImportJobStatus = scmService.importStatusForJobs(results.nextScheduled)
                        pluginData.scmImportStatus = scmService.importPluginStatus(authContext, params.project)
                        pluginData.scmImportActions = scmService.importPluginActions(authContext, params.project)
                        results.putAll(pluginData)
                    }

                } catch (ScmPluginException e) {
                    results.warning = "Failed to update SCM Import status: ${e.message}"
                }
            }
        }

        if(usedFilter){
            results.filterName=usedFilter
            results.paginateParams['filterName']=usedFilter
        }
        results.params=params

        def remoteClusterNodeUUID=null
        if (frameworkService.isClusterModeEnabled()) {
            results.serverClusterNodeUUID = frameworkService.getServerUUID()
        }
        log.debug("jobsFragment(tot): "+(System.currentTimeMillis()-start));
        return results
    }
    /**
     * Presents the jobs tree and can pass the jobsjscallback parameter
     * to the be a javascript callback for clicked jobs, instead of normal behavior.
     */
    def jobsPicker(ScheduledExecutionQuery query) {

        AuthContext authContext
        def usedFilter=null
        if(!query){
            query = new ScheduledExecutionQuery()
        }
        if(query && !query.projFilter && params.project) {
            query.projFilter = params.project
            authContext = frameworkService.getAuthContextForSubjectAndProject(session.subject, params.project)
        } else if(query && query.projFilter){
            authContext = frameworkService.getAuthContextForSubjectAndProject(session.subject, query.projFilter)
        } else {
            authContext = frameworkService.getAuthContextForSubject(session.subject)
        }
        def results=listWorkflows(query,authContext,session.user)
        if(usedFilter){
            results.filterName=usedFilter
            results.paginateParams['filterName']=usedFilter
        }
        results.params=params
        if(params.jobsjscallback){
            results.jobsjscallback=params.jobsjscallback
        }
        results.showemptymessage=true
        return results + [runAuthRequired:params.runAuthRequired]
    }

    public def jobsSearchJson(ScheduledExecutionQuery query) {
        AuthContext authContext
        def usedFilter = null
        if (!query) {
            query = new ScheduledExecutionQuery()
        }
        if (query && !query.projFilter && params.project) {
            query.projFilter = params.project
            authContext = frameworkService.getAuthContextForSubjectAndProject(session.subject, params.project)
        } else if (query && query.projFilter) {
            authContext = frameworkService.getAuthContextForSubjectAndProject(session.subject, query.projFilter)
        } else {
            authContext = frameworkService.getAuthContextForSubject(session.subject)
        }
        def results = listWorkflows(query, authContext, session.user)
        def runRequired = params.runAuthRequired
        def jobs = runRequired == 'true' ? results.nextScheduled.findAll { se ->
            results.jobauthorizations[AuthConstants.ACTION_RUN]?.contains(se.id.toString())
        } : results.nextScheduled
        def formatted = jobs.collect {ScheduledExecution job->
            [name: job.jobName, group: job.groupPath, project: job.project, id: job.extid]
        }
        respond(
                [formats: ['json']],
                formatted,
        )
    }
    private def listWorkflows(ScheduledExecutionQuery query,AuthContext authContext,String user) {
        long start=System.currentTimeMillis()
        if(null!=query){
            query.configureFilter()
        }
        def qres = scheduledExecutionService.listWorkflows(query)
        log.debug("service.listWorkflows: "+(System.currentTimeMillis()-start));
        long rest=System.currentTimeMillis()
        def schedlist=qres.schedlist
        def total=qres.total
        def filters=qres._filters
        
        def finishq=scheduledExecutionService.finishquery(query,params,qres)

        def allScheduled = schedlist.findAll { it.scheduled }
        def nextExecutions=scheduledExecutionService.nextExecutionTimes(allScheduled)
        def clusterMap=scheduledExecutionService.clusterScheduledJobs(allScheduled)
        log.debug("listWorkflows(nextSched): "+(System.currentTimeMillis()-rest));
        long preeval=System.currentTimeMillis()

        //collect all jobs and authorize the user for the set of available Job actions
        def jobnames=[:]
        Set res = new HashSet()
        schedlist.each{ ScheduledExecution sched->
            if(!jobnames[sched.generateFullName()]){
                jobnames[sched.generateFullName()]=[]
            }
            jobnames[sched.generateFullName()]<<sched.id.toString()
            res.add(frameworkService.authResourceForJob(sched))
        }
        // Filter the groups by what the user is authorized to see.

        def decisions = frameworkService.authorizeProjectResources(authContext,res, new HashSet([AuthConstants.ACTION_READ, AuthConstants.ACTION_DELETE, AuthConstants.ACTION_RUN, AuthConstants.ACTION_UPDATE, AuthConstants.ACTION_KILL]),query.projFilter)
        log.debug("listWorkflows(evaluate): "+(System.currentTimeMillis()-preeval));

        long viewable=System.currentTimeMillis()

        def authCreate = frameworkService.authorizeProjectResource(authContext,
                AuthConstants.RESOURCE_TYPE_JOB,
                AuthConstants.ACTION_CREATE, query.projFilter)
        

        def Map jobauthorizations=[:]

        //produce map: [actionName:[id1,id2,...],actionName2:[...]] for all allowed actions for jobs
        decisions.findAll { it.authorized}.groupBy { it.action }.each{k,v->
            jobauthorizations[k] = new HashSet(v.collect {
                jobnames[ScheduledExecution.generateFullName(it.resource.group,it.resource.name)]
            }.flatten())
        }

        jobauthorizations[AuthConstants.ACTION_CREATE]=authCreate
        def authorizemap=[:]
        def pviewmap=[:]
        def newschedlist=[]
        def unauthcount=0
        def readauthcount=0

        /*
         'group/name' -> [ jobs...]
         */
        def jobgroups=[:]
        schedlist.each{ ScheduledExecution se->
            authorizemap[se.id.toString()]=jobauthorizations[AuthConstants.ACTION_READ]?.contains(se.id.toString())
            if(authorizemap[se.id.toString()]){
                newschedlist<<se
                if(!jobgroups[se.groupPath?:'']){
                    jobgroups[se.groupPath?:'']=[se]
                }else{
                    jobgroups[se.groupPath?:'']<<se
                }
            }
            if(!authorizemap[se.id.toString()]){
                log.debug("Unauthorized job: ${se}")
                unauthcount++
            }

        }
        readauthcount= newschedlist.size()

        if(grailsApplication.config.rundeck?.gui?.realJobTree != "false") {
            //Adding group entries for empty hierachies to have a "real" tree 
            def missinggroups = [:]
            jobgroups.each { k, v ->
                def splittedgroups = k.split('/')
                splittedgroups.eachWithIndex { item, idx ->
                    def thepath = splittedgroups[0..idx].join('/')
                    if(!jobgroups.containsKey(thepath)) {
                        missinggroups[thepath]=[]
                    }
                }
            }
            //sorting is done in the view
            jobgroups.putAll(missinggroups)
        }

        schedlist=newschedlist
        log.debug("listWorkflows(viewable): "+(System.currentTimeMillis()-viewable));
        long last=System.currentTimeMillis()

        log.debug("listWorkflows(last): "+(System.currentTimeMillis()-last));
        log.debug("listWorkflows(total): "+(System.currentTimeMillis()-start));

        return [
        nextScheduled:schedlist,
        nextExecutions: nextExecutions,
                clusterMap: clusterMap,
        jobauthorizations:jobauthorizations,
        authMap:authorizemap,
        jobgroups:jobgroups,
        paginateParams:finishq.paginateParams,
        displayParams:finishq.displayParams,
        total: total,
        max: finishq.max,
        offset:finishq.offset,
        unauthorizedcount:unauthcount,
        totalauthorized: readauthcount,
        ]
    }
    
    
    def storeJobfilter(ScheduledExecutionQuery query, StoreFilterCommand storeFilterCommand){
        withForm{
        if (storeFilterCommand.hasErrors()) {
            flash.errors = storeFilterCommand.errors
            params.saveFilter = true
            return redirect(controller: 'menu', action: params.fragment ? 'jobsFragment' : 'jobs',
                    params: params.subMap(['newFilterName', 'existsFilterName', 'project', 'saveFilter']))
        }

        def User u = userService.findOrCreateUser(session.user)
        def ScheduledExecutionFilter filter
        def boolean saveuser=false
        if(params.newFilterName && !params.existsFilterName){
            filter= ScheduledExecutionFilter.fromQuery(query)
            filter.name=params.newFilterName
            filter.user=u
            if(!filter.validate()){
                flash.error=filter.errors.allErrors.collect { g.message(error:it).encodeAsHTML()}.join("<br>")
                params.saveFilter=true
                return redirect(controller:'menu',action:params.fragment?'jobsFragment':'jobs',params:params)
            }
            u.addToJobfilters(filter)
            saveuser=true
        }else if(params.existsFilterName){
            filter = ScheduledExecutionFilter.findByNameAndUser(params.existsFilterName,u)
            if(filter){
                filter.properties=query.properties
                filter.fix()
            }
        }else if(!params.newFilterName && !params.existsFilterName){
            flash.error="Filter name not specified"
            params.saveFilter=true
            return redirect(controller:'menu',action:params.fragment?'jobsFragment':'jobs',params:params)
        }
        if(!filter.save(flush:true)){
            flash.error=filter.errors.allErrors.collect { g.message(error:it)
            }.join("<br>")
            params.saveFilter=true
            return redirect(controller:'menu',action:params.fragment?'jobsFragment':'jobs',params:params)
        }
        if(saveuser){
            if(!u.save(flush:true)){
                return renderErrorView(filter.errors.allErrors.collect { g.message(error: it).encodeAsHTML() }.join("\n"))
            }
        }
        redirect(controller:'menu',action:params.fragment?'jobsFragment':'jobs',params:[filterName:filter.name,project:params.project])
        }.invalidToken {
            renderErrorView(g.message(code:'request.error.invalidtoken.message'))
        }
    }
    
    
    def deleteJobfilter={
        withForm{
        def User u = userService.findOrCreateUser(session.user)
        def filtername=params.delFilterName
        final def ffilter = ScheduledExecutionFilter.findByNameAndUser(filtername, u)
        if(ffilter){
            ffilter.delete(flush:true)
            flash.message="Filter deleted: ${filtername.encodeAsHTML()}"
        }
        redirect(controller:'menu',action:params.fragment?'jobsFragment':'jobs',params:[project: params.project])
        }.invalidToken{
            renderErrorView(g.message(code:'request.error.invalidtoken.message'))
        }
    }

    def executionMode(){
        def executionModeActive=configurationService.executionModeActive

        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)
        def authAction=executionModeActive?AuthConstants.ACTION_DISABLE_EXECUTIONS:AuthConstants.ACTION_ENABLE_EXECUTIONS

        if (unauthorizedResponse(
                frameworkService.authorizeApplicationResourceAny(
                        authContext,
                        AuthConstants.RESOURCE_TYPE_SYSTEM,
                        [authAction, AuthConstants.ACTION_ADMIN]
                ),
                authAction, 'for', 'Rundeck')) {
            return
        }


    }
    def storage(){
//        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)
//        if (unauthorizedResponse(
//                frameworkService.authorizeApplicationResourceAny(authContext,
//                        frameworkService.authResourceForProject(params.project),
//                        [AuthConstants.ACTION_CONFIGURE, AuthConstants.ACTION_ADMIN, AuthConstants.ACTION_IMPORT,
//                                AuthConstants.ACTION_EXPORT, AuthConstants.ACTION_DELETE]),
//                AuthConstants.ACTION_ADMIN, 'Project', params.project)) {
//            return
//        }

    }

    def projectExport() {
        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)
        if (!params.project) {
            return renderErrorView('Project parameter is required')
        }
        if (unauthorizedResponse(
                frameworkService.authorizeApplicationResourceAny(
                        authContext,
                        frameworkService.authResourceForProject(params.project),
                        [AuthConstants.ACTION_ADMIN, AuthConstants.ACTION_EXPORT]
                ),
                AuthConstants.ACTION_EXPORT, 'Project', params.project
        )) {
            return
        }
    }
    def projectImport() {
        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)
        if (!params.project) {
            return renderErrorView('Project parameter is required')
        }
        if (unauthorizedResponse(
                frameworkService.authorizeApplicationResourceAny(
                        authContext,
                        frameworkService.authResourceForProject(params.project),
                        [AuthConstants.ACTION_ADMIN, AuthConstants.ACTION_IMPORT]
                ),
                AuthConstants.ACTION_IMPORT, 'Project', params.project
        )) {
            return
        }
    }
    def projectDelete() {
        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)
        if (!params.project) {
            return renderErrorView('Project parameter is required')
        }
        if (unauthorizedResponse(
                frameworkService.authorizeApplicationResourceAny(
                        authContext,
                        frameworkService.authResourceForProject(params.project),
                        [AuthConstants.ACTION_ADMIN, AuthConstants.ACTION_DELETE]
                ),
                AuthConstants.ACTION_DELETE, 'Project', params.project
        )) {
            return
        }
    }

    public def resumeIncompleteLogStorage(Long id){
        withForm{

            AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)

            if (unauthorizedResponse(
                    frameworkService.authorizeApplicationResourceAny(
                            authContext,
                            AuthConstants.RESOURCE_TYPE_SYSTEM,
                            [ AuthConstants.ACTION_ADMIN]
                    ),
                    AuthConstants.ACTION_ADMIN, 'for', 'Rundeck')) {
                return
            }
            logFileStorageService.resumeIncompleteLogStorageAsync(frameworkService.serverUUID,id)
//            logFileStorageService.resumeCancelledLogStorageAsync(frameworkService.serverUUID)
            flash.message="Resumed log storage requests"
            return redirect(action: 'logStorage', params: [project: params.project])

        }.invalidToken{

            request.error=g.message(code:'request.error.invalidtoken.message')
            renderErrorView([:])
        }
    }
    public def resumeIncompleteLogStorageAjax(Long id){
        withForm{

            g.refreshFormTokensHeader()

            AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)

            if (!apiService.requireAuthorized(
                    frameworkService.authorizeApplicationResourceAny(
                            authContext,
                            AuthConstants.RESOURCE_TYPE_SYSTEM,
                            [ AuthConstants.ACTION_ADMIN]
                    ),
                    response,
                    [AuthConstants.ACTION_ADMIN, 'for', 'Rundeck'].toArray())) {
                return
            }
            logFileStorageService.resumeIncompleteLogStorageAsync(frameworkService.serverUUID,id)
//            logFileStorageService.resumeCancelledLogStorageAsync(frameworkService.serverUUID)
            def message="Resumed log storage requests"
            LogFileStorageRequest req=null
            if(id){
                req=LogFileStorageRequest.get(id)
            }
            withFormat{
                ajax{
                    render(contentType: 'application/json'){
                        status='ok'
                        delegate.message=message
                        if(req){
                            contents = exportRequestMap(req, true, false, null)
                        }
                    }
                }
            }

        }.invalidToken{

            return apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_BAD_REQUEST,
                    code: 'request.error.invalidtoken.message',
            ])
        }
    }
    def logStorage() {
        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)

        if (unauthorizedResponse(
                frameworkService.authorizeApplicationResource(authContext, AuthConstants.RESOURCE_TYPE_SYSTEM,
                                                              AuthConstants.ACTION_READ
                ),
                AuthConstants.ACTION_READ, 'System configuration'
        )) {
            return
        }
    }
    /**
     * Remove outstanding queued requests
     * @return
     */
    def haltIncompleteLogStorage(){
        withForm{
            AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)

            if (unauthorizedResponse(
                    frameworkService.authorizeApplicationResourceAny(
                            authContext,
                            AuthConstants.RESOURCE_TYPE_SYSTEM,
                            [ AuthConstants.ACTION_ADMIN]
                    ),
                    AuthConstants.ACTION_ADMIN, 'for', 'Rundeck')) {
                return
            }
            logFileStorageService.haltIncompleteLogStorage(frameworkService.serverUUID)
            flash.message="Unqueued incomplete log storage requests"
            return redirect(action: 'logStorage', params: [project: params.project])
        }.invalidToken{

            request.error=g.message(code:'request.error.invalidtoken.message')
            renderErrorView([:])

        }
    }
    def cleanupIncompleteLogStorage(Long id){
        withForm{
            AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)

            if (unauthorizedResponse(
                    frameworkService.authorizeApplicationResourceAny(
                            authContext,
                            AuthConstants.RESOURCE_TYPE_SYSTEM,
                            [ AuthConstants.ACTION_ADMIN]
                    ),
                    AuthConstants.ACTION_ADMIN, 'for', 'Rundeck')) {
                return
            }
            def count=logFileStorageService.cleanupIncompleteLogStorage(frameworkService.serverUUID,id)
            flash.message="Removed $count log storage requests"
            return redirect(action: 'logStorage', params: [project: params.project])
        }.invalidToken{

            request.error=g.message(code:'request.error.invalidtoken.message')
            renderErrorView([:])

        }
    }
    public def cleanupIncompleteLogStorageAjax(Long id){
        withForm{

            g.refreshFormTokensHeader()

            AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)

            if (!apiService.requireAuthorized(
                    frameworkService.authorizeApplicationResourceAny(
                            authContext,
                            AuthConstants.RESOURCE_TYPE_SYSTEM,
                            [ AuthConstants.ACTION_ADMIN]
                    ),
                    response,
                    [AuthConstants.ACTION_ADMIN, 'for', 'Rundeck'].toArray())) {
                return
            }
            def count=logFileStorageService.cleanupIncompleteLogStorage(frameworkService.serverUUID,id)
            def message="Removed $count log storage requests"
            withFormat{
                ajax{
                    render(contentType: 'application/json'){
                        status='ok'
                        delegate.message=message
                    }
                }
            }

        }.invalidToken{

            return apiService.renderErrorFormat(response, [
                    status: HttpServletResponse.SC_BAD_REQUEST,
                    code: 'request.error.invalidtoken.message',
            ])
        }
    }
    def logStorageIncompleteAjax(BaseQuery query){
        if('true'!=request.getHeader('x-rundeck-ajax')) {
            return redirect(action: 'logStorage',controller: 'menu',params: params)
        }
        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)

        if (unauthorizedResponse(
                frameworkService.authorizeApplicationResource(authContext, AuthConstants.RESOURCE_TYPE_SYSTEM,
                                                              AuthConstants.ACTION_READ
                ),
                AuthConstants.ACTION_READ, 'System configuration'
        )) {
            return
        }
        def total=logFileStorageService.countIncompleteLogStorageRequests()
        def list = logFileStorageService.listIncompleteRequests(
                frameworkService.serverUUID,
                [max: query.max, offset: query.offset]
        )
        def queuedIds=logFileStorageService.getQueuedIncompleteRequestIds()
        def failedIds=logFileStorageService.getFailedRequestIds()
        withFormat{
            json{
                render(contentType: "application/json") {
                    incompleteRequests {
                        delegate.'total' = total
                        max = params.max ?: 20
                        offset = params.offset ?: 0
                        contents = list.collect { LogFileStorageRequest req ->
                            exportRequestMap(
                                    req,
                                    queuedIds.contains(req.id),
                                    failedIds.contains(req.id),
                                    failedIds.contains(req.id) ? logFileStorageService.getFailures(req.id) : null
                            )
                        }
                    }
                }
            }
        }
    }

    private LinkedHashMap<String, Object> exportRequestMap(LogFileStorageRequest req, isQueued, isFailed, messages) {
        [
                id               : req.id,
                executionId      : req.execution.id,
                project          : req.execution.project,
                href             : apiService.apiHrefForExecution(req.execution),
                permalink        : apiService.guiHrefForExecution(req.execution),
                dateCreated      : req.dateCreated,
                completed        : req.completed,
                filetype         : req.filetype,
                localFilesPresent: logFileStorageService.areAllExecutionFilesPresent(req.execution),
                queued           : isQueued,
                failed           : isFailed,
                messages         : messages
        ]
    }

    def logStorageMissingAjax(BaseQuery query){
        if('true'!=request.getHeader('x-rundeck-ajax')) {
            return redirect(action: 'logStorage',controller: 'menu',params: params)
        }
        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)

        if (unauthorizedResponse(
                frameworkService.authorizeApplicationResource(authContext, AuthConstants.RESOURCE_TYPE_SYSTEM,
                                                              AuthConstants.ACTION_READ
                ),
                AuthConstants.ACTION_READ, 'System configuration'
        )) {
            return
        }
        def totalc=logFileStorageService.countExecutionsWithoutStorageRequests(frameworkService.serverUUID)
        def list = logFileStorageService.listExecutionsWithoutStorageRequests(
                frameworkService.serverUUID,
                [max: query.max, offset: query.offset]
        )
        withFormat{
            json{
                render(contentType: "application/json") {
                    missingRequests {
                        total = totalc
                        max = params.max ?: 20
                        offset = params.offset ?: 0
                        contents = list.collect { Execution req ->
                            [
                                    id     : req.id,
                                    project: req.project,
                                    href   : createLink(
                                            action: 'show',
                                            controller: 'execution',
                                            params: [project: req.project, id: req.id]
                                    ),
//                                    summary: executionService.summarizeJob(req.scheduledExecution, req)
                            ]
                        }
                    }
                }
            }
        }
    }
    def logStorageAjax(){
        if('true'!=request.getHeader('x-rundeck-ajax')) {
            return redirect(action: 'logStorage',controller: 'menu',params: params)
        }
        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)

        if (unauthorizedResponse(
                frameworkService.authorizeApplicationResource(authContext, AuthConstants.RESOURCE_TYPE_SYSTEM,
                                                              AuthConstants.ACTION_READ
                ),
                AuthConstants.ACTION_READ, 'System configuration'
        )) {
            return
        }
        def data = logFileStorageService.getStorageStats()
        data.retryDelay=logFileStorageService.getConfiguredStorageRetryDelay()
        return render(contentType: 'application/json', text: data + [enabled: data.pluginName ? true : false] as JSON)
    }
    def systemConfig(){
        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)

        if (unauthorizedResponse(
                frameworkService.authorizeApplicationResource(authContext, AuthConstants.RESOURCE_TYPE_SYSTEM,
                        AuthConstants.ACTION_READ),
                AuthConstants.ACTION_READ, 'System configuration')) {
            return
        }
        [rundeckFramework: frameworkService.rundeckFramework]
    }

    def securityConfig() {
        return redirect(action: 'acls', params: params)
    }

    def projectAcls() {
        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)

        if (!params.project) {
            return renderErrorView('Project parameter is required')
        }
        if (unauthorizedResponse(
                frameworkService.authorizeApplicationResourceAny(
                        authContext,
                        frameworkService.authResourceForProjectAcl(params.project),
                        [AuthConstants.ACTION_READ, AuthConstants.ACTION_ADMIN]
                ),
                AuthConstants.ACTION_READ, 'ACL for Project', params.project
        )) {
            return
        }
        def project = frameworkService.getFrameworkProject(params.project)
        if(notFoundResponse(project,'Project',params.project)){
           return
        }
        def projectlist = project.listDirPaths('acls/').findAll { it ==~ /.*\.aclpolicy$/ }.collect {
            it.replaceAll(/^acls\//, '')
        }

        [
                rundeckFramework: frameworkService.rundeckFramework,
                aclFileList     : list,
                assumeValid     : true,
                acllist         : projectlist
        ]
    }

    def acls() {
        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)

        if (unauthorizedResponse(
                frameworkService.authorizeApplicationResource(authContext, AuthConstants.RESOURCE_TYPE_SYSTEM,
                        AuthConstants.ACTION_READ),
                AuthConstants.ACTION_READ, 'System configuration')) {
            return
        }
        def fwkConfigDir=frameworkService.rundeckFramework.getConfigDir()
        def list=fwkConfigDir.listFiles().grep{it.name=~/\.aclpolicy$/}.sort()
        Map<File,Validation> validation=list.collectEntries{
            [it,authorizationService.validateYamlPolicy(it)]
        }

        [
                rundeckFramework: frameworkService.rundeckFramework,
                fwkConfigDir    : fwkConfigDir,
                aclFileList     : list,
                validations     : validation,
        ]
    }

    def systemInfo (){
        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)

        if (unauthorizedResponse(
                frameworkService.authorizeApplicationResource(authContext, AuthConstants.RESOURCE_TYPE_SYSTEM,
                        AuthConstants.ACTION_READ),
                AuthConstants.ACTION_READ, 'System configuration')) {
            return
        }

        Date nowDate = new Date();
        String nodeName = servletContext.getAttribute("FRAMEWORK_NODE")
        String appVersion = grailsApplication.metadata['app.version']
        double load = ManagementFactory.getOperatingSystemMXBean().systemLoadAverage
        int processorsCount = ManagementFactory.getOperatingSystemMXBean().availableProcessors
        String osName = ManagementFactory.getOperatingSystemMXBean().name
        String osVersion = ManagementFactory.getOperatingSystemMXBean().version
        String osArch = ManagementFactory.getOperatingSystemMXBean().arch
        String javaVendor = System.properties['java.vendor']
        String javaVersion = System.properties['java.version']
        String vmName = ManagementFactory.getRuntimeMXBean().vmName
        String vmVendor = ManagementFactory.getRuntimeMXBean().vmVendor
        String vmVersion = ManagementFactory.getRuntimeMXBean().vmVersion
        long durationTime = ManagementFactory.getRuntimeMXBean().uptime
        Date startupDate = new Date(nowDate.getTime() - durationTime)
        int threadActiveCount = Thread.activeCount()
        def build = grailsApplication.metadata['build.ident']
        def base = servletContext.getAttribute("RDECK_BASE")
        boolean executionModeActive=configurationService.executionModeActive

        def memmax = Runtime.getRuntime().maxMemory()
        def memfree = Runtime.getRuntime().freeMemory()
        def memtotal = Runtime.getRuntime().totalMemory()
        def schedulerRunningCount = quartzScheduler.getCurrentlyExecutingJobs().size()
        def threadPoolSize = quartzScheduler.getMetaData().threadPoolSize
        def info = [
            nowDate: nowDate,
            nodeName: nodeName,
            appVersion: appVersion,
            load: load,
            processorsCount: processorsCount,
            osName: osName,
            osVersion: osVersion,
            osArch: osArch,
            javaVendor: javaVendor,
            javaVersion: javaVersion,
            vmName: vmName,
            vmVendor: vmVendor,
            vmVersion: vmVersion,
            durationTime: durationTime,
            startupDate: startupDate,
            threadActiveCount: threadActiveCount,
            build: build,
            base: base,
            memmax: memmax,
            memfree: memfree,
            memtotal: memtotal,
            schedulerRunningCount: schedulerRunningCount,
            threadPoolSize: threadPoolSize,
            executionModeActive:executionModeActive
        ]
        def schedulerThreadRatio=info.threadPoolSize>0?(info.schedulerRunningCount/info.threadPoolSize):0
        def serverUUID=frameworkService.getServerUUID()
        if(serverUUID){
            info['serverUUID']=serverUUID
        }
        return [
                schedulerThreadRatio:schedulerThreadRatio,
                schedulerRunningCount:info.schedulerRunningCount,
                threadPoolSize:info.threadPoolSize,
                systemInfo: [

            [
                "stats: uptime": [
                    duration: info.durationTime,
                    'duration.unit': 'ms',
                    datetime: g.w3cDateValue(date: info.startupDate)
                ]],

            ["stats: cpu": [

                loadAverage: info.load,
                'loadAverage.unit': '%',
                processors: info.processorsCount
            ]],
            ["stats: memory":
            [
                'max.unit': 'byte',
                'free.unit': 'byte',
                'total.unit': 'byte',
                max: info.memmax,
                free: info.memfree,
                total: info.memtotal,
                used: info.memtotal-info.memfree,
                'used.unit': 'byte',
                heapusage: (info.memtotal-info.memfree)/info.memtotal,
                'heapusage.unit': 'ratio',
                'heapusage.info': 'Ratio of Used to Free memory within the Heap (used/total)',
                allocated: (info.memtotal)/info.memmax,
                'allocated.unit': 'ratio',
                'allocated.info': 'Ratio of system memory allocated to maximum allowed (total/max)',
            ]],
            ["stats: scheduler":
            [
                    running: info.schedulerRunningCount,
                    threadPoolSize:info.threadPoolSize,
                    ratio: (schedulerThreadRatio),
                    'ratio.unit':'ratio',
                    'ratio.info':'Ratio of used threads to Quartz scheduler thread pool size'
            ]
            ],
            ["stats: threads":
            [active: info.threadActiveCount]
            ],
            [timestamp: [
//                epoch: info.nowDate.getTime(), 'epoch.unit': 'ms',
                datetime: g.w3cDateValue(date: info.nowDate)
            ]],

            [rundeck:
            [version: info.appVersion,
                build: info.build,
                node: info.nodeName,
                base: info.base,
                serverUUID: info.serverUUID,
            ]],
            [
                    executions:[
                            active: info.executionModeActive,
                            executionMode:info.executionModeActive?'ACTIVE':'PASSIVE',
                            'executionMode.status':info.executionModeActive?'success':'warning'
                    ]
            ],
            [os:
            [arch: info.osArch,
                name: info.osName,
                version: info.osVersion,
            ]
            ],
            [jvm:
            [
                vendor: info.javaVendor,
                version: info.javaVersion,
                name: info.vmName,
                implementationVersion: info.vmVersion
            ],
            ],
        ]]
    }

    def metrics(){

    }
    def licenses(){

    }

    def plugins(){
        //list plugins and config settings for project/framework props

        Framework framework = frameworkService.getRundeckFramework()

        //framework level plugin descriptions
        def pluginDescs= [
            framework.getNodeExecutorService(),
            framework.getFileCopierService(),
            framework.getNodeStepExecutorService(),
            framework.getStepExecutionService(),
            framework.getResourceModelSourceService(),
            framework.getResourceFormatParserService(),
            framework.getResourceFormatGeneratorService(),
            framework.getOrchestratorService(),
        ].collectEntries{
            [it.name, it.listDescriptions().sort {a,b->a.name<=>b.name}]
        }

        //web-app level plugin descriptions
        pluginDescs[notificationService.notificationPluginProviderService.name]=notificationService.listNotificationPlugins().collect {
            it.value.description
        }.sort { a, b -> a.name <=> b.name }
        pluginDescs[loggingService.streamingLogReaderPluginProviderService.name]=loggingService.listStreamingReaderPlugins().collect {
            it.value.description
        }.sort { a, b -> a.name <=> b.name }
        pluginDescs[loggingService.streamingLogWriterPluginProviderService.name]=loggingService.listStreamingWriterPlugins().collect {
            it.value.description
        }.sort { a, b -> a.name <=> b.name }
        pluginDescs[logFileStorageService.executionFileStoragePluginProviderService.name]= logFileStorageService.listLogFileStoragePlugins().collect {
            it.value.description
        }.sort { a, b -> a.name <=> b.name }
        pluginDescs[storagePluginProviderService.name]= pluginService.listPlugins(StoragePlugin.class,storagePluginProviderService).collect {
            it.value.description
        }.sort { a, b -> a.name <=> b.name }
        pluginDescs[storageConverterPluginProviderService.name] = pluginService.listPlugins(StorageConverterPlugin.class, storageConverterPluginProviderService).collect {
            it.value.description
        }.sort { a, b -> a.name <=> b.name }
        pluginDescs[scmService.scmExportPluginProviderService.name]=scmService.listPlugins('export').collect {
            it.value.description
        }.sort { a, b -> a.name <=> b.name }
        pluginDescs[scmService.scmImportPluginProviderService.name]=scmService.listPlugins('import').collect {
            it.value.description
        }.sort { a, b -> a.name <=> b.name }

        pluginDescs['FileUploadPluginService']=pluginService.listPlugins(FileUploadPlugin).collect {
            it.value.description
        }.sort { a, b -> a.name <=> b.name }


        def uiPluginProfiles = [:]
        def loadedFileNameMap=[:]
        pluginDescs.each { svc, list ->
            list.each { desc ->
                def provIdent = svc + ":" + desc.name
                uiPluginProfiles[provIdent] = uiPluginService.getProfileFor(svc, desc.name)
                def filename = uiPluginProfiles[provIdent].metadata?.filename
                if(filename){
                    if(!loadedFileNameMap[filename]){
                        loadedFileNameMap[filename]=[]
                    }
                    loadedFileNameMap[filename]<< provIdent
                }
            }
        }

        def defaultScopes=[
                (framework.getNodeStepExecutorService().name) : PropertyScope.InstanceOnly,
                (framework.getStepExecutionService().name) : PropertyScope.InstanceOnly,
        ]
        def bundledPlugins=[
                (framework.getNodeExecutorService().name): framework.getNodeExecutorService().getBundledProviderNames(),
                (framework.getFileCopierService().name): framework.getFileCopierService().getBundledProviderNames(),
                (framework.getResourceFormatParserService().name): framework.getResourceFormatParserService().getBundledProviderNames(),
                (framework.getResourceFormatGeneratorService().name): framework.getResourceFormatGeneratorService().getBundledProviderNames(),
                (framework.getResourceModelSourceService().name): framework.getResourceModelSourceService().getBundledProviderNames(),
                (storagePluginProviderService.name): storagePluginProviderService.getBundledProviderNames()+['db'],
                FileUploadPluginService: ['filesystem-temp'],
        ]
        //list included plugins
        def embeddedList = frameworkService.listEmbeddedPlugins(grailsApplication)
        def embeddedFilenames=[]
        if(embeddedList.success && embeddedList.pluginList){
            embeddedFilenames=embeddedList.pluginList*.fileName
        }
        def specialConfiguration=[
                (storagePluginProviderService.name):[
                        description: message(code:"plugin.storage.provider.special.description"),
                        prefix:"rundeck.storage.provider.[index].config."
                ],
                (storageConverterPluginProviderService.name):[
                        description: message(code:"plugin.storage.converter.special.description"),
                        prefix:"rundeck.storage.converter.[index].config."
                ],
                (framework.getResourceModelSourceService().name):[
                        description: message(code:"plugin.resourceModelSource.special.description"),
                        prefix:"resources.source.[index].config."
                ],
                (logFileStorageService.executionFileStoragePluginProviderService.name):[
                        description: message(code:"plugin.executionFileStorage.special.description"),
                ],
                (scmService.scmExportPluginProviderService.name):[
                        description: message(code:"plugin.scmExport.special.description"),
                ],
                (scmService.scmImportPluginProviderService.name):[
                        description: message(code:"plugin.scmImport.special.description"),
                ],
                FileUploadPluginService:[
                        description: message(code:"plugin.FileUploadPluginService.special.description"),
                ]
        ]
        def specialScoping=[
                (scmService.scmExportPluginProviderService.name):true,
                (scmService.scmImportPluginProviderService.name):true
        ]

        [
                descriptions        : pluginDescs,
                serviceDefaultScopes: defaultScopes,
                bundledPlugins      : bundledPlugins,
                embeddedFilenames   : embeddedFilenames,
                specialConfiguration: specialConfiguration,
                specialScoping      : specialScoping,
                uiPluginProfiles    : uiPluginProfiles
        ]
    }

    def home() {
        //first-run html info
        def isFirstRun=false

        if(configurationService.getBoolean("startup.detectFirstRun",true) &&
                frameworkService.rundeckFramework.hasProperty('framework.var.dir')) {
            def vardir = frameworkService.rundeckFramework.getProperty('framework.var.dir')
            def vers = grailsApplication.metadata['build.ident'].replaceAll('\\s+\\(.+\\)$','')
            def file = new File(vardir, ".first-run-${vers}")
            if(!file.exists()){
                isFirstRun=true
                file.withWriter("UTF-8"){out->
                    out.write('#'+(new Date().toString()))
                }
            }
        }
        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)
        long start = System.currentTimeMillis()
        def fprojects = frameworkService.projectNames(authContext)
        session.frameworkProjects = fprojects
        log.debug("frameworkService.projectNames(context)... ${System.currentTimeMillis() - start}")
        def stats=cachedSummaryProjectStats(fprojects)

        render(view: 'home', model: [
                isFirstRun:isFirstRun,
                projectNames: fprojects,
                execCount:stats.execCount,
                totalFailedCount:stats.totalFailedCount,
                recentUsers:stats.recentUsers,
                recentProjects:stats.recentProjects
        ])
    }

    def projectHome() {
        if (!params.project) {
            return redirect(controller: 'menu', action: 'home')
        }
        [project: params.project]
    }

    def welcome(){
    }

    private def cachedSummaryProjectStats(final List projectNames) {
        long now = System.currentTimeMillis()
        if (null == session.summaryProjectStats ||
                session.summaryProjectStats_expire < now ||
                session.summaryProjectStatsSize != projectNames.size()) {
            if (configurationService.getBoolean("menuController.projectStats.queryAlt", false)) {
                session.summaryProjectStats = metricService?.withTimer(
                        MenuController.name,
                        'loadSummaryProjectStatsAlt'
                ) {
                    loadSummaryProjectStatsAlt(projectNames)
                } ?: loadSummaryProjectStatsAlt(projectNames)
            } else {
                session.summaryProjectStats = metricService?.withTimer(
                        MenuController.name,
                        'loadSummaryProjectStatsOrig'
                ) {
                    loadSummaryProjectStatsOrig(projectNames)
                } ?: loadSummaryProjectStatsOrig(projectNames)
            }
            session.summaryProjectStatsSize = projectNames.size()
            session.summaryProjectStats_expire = now + (60 * 1000)
        }
        return session.summaryProjectStats
    }

    private def loadSummaryProjectStatsAlt(final List projectNames) {
        long start = System.currentTimeMillis()
        Calendar n = GregorianCalendar.getInstance()
        n.add(Calendar.DAY_OF_YEAR, -1)
        Date lastday = n.getTime()
        def summary = [:]
        def projects = new TreeSet()
        projectNames.each { project ->
            summary[project] = [name: project, execCount: 0, failedCount: 0, userSummary: [], userCount: 0]
        }
        long proj2 = System.currentTimeMillis()
        def executionResults = Execution.createCriteria().list {
            gt('dateStarted', lastday)
            inList('project', projectNames)
            projections {
                property('project')
                property('status')
                property('user')
            }
        }
        proj2 = System.currentTimeMillis() - proj2
        long proj3 = System.currentTimeMillis()
        def projexecs = executionResults.groupBy { it[0] }
        proj3 = System.currentTimeMillis() - proj3

        def execCount = executionResults.size()
        def totalFailedCount = 0
        def users = new HashSet<String>()
        projexecs.each { name, val ->
            if (summary[name]) {
                summary[name.toString()].execCount = val.size()
                projects << name
                def failedlist = val.findAll { it[1] in ['false', 'failed'] }

                summary[name.toString()].failedCount = failedlist.size()
                totalFailedCount += failedlist.size()
                def projusers = new HashSet<String>()
                projusers.addAll(val.collect { it[2] })

                summary[name.toString()].userSummary.addAll(projusers)
                summary[name.toString()].userCount = projusers.size()
                users.addAll(projusers)
            }
        }

        log.error(
                "loadSummaryProjectStats... ${System.currentTimeMillis() - start}, projexeccount ${proj2}, " +
                        "projuserdistinct ${proj3}"
        )
        [summary: summary, recentUsers: users, recentProjects: projects, execCount: execCount, totalFailedCount: totalFailedCount]
    }
    /**
     *
     * @param projectNames
     * @return
     */
    private def loadSummaryProjectStatsOrig(final List projectNames) {
        long start=System.currentTimeMillis()
        Calendar n = GregorianCalendar.getInstance()
        n.add(Calendar.DAY_OF_YEAR, -1)
        Date today = n.getTime()
        def summary=[:]
        def projects = []
        projectNames.each{project->
            summary[project]=[name: project, execCount: 0, failedCount: 0,userSummary: [], userCount: 0]
        }
        long proj2=System.currentTimeMillis()
        def projects2 = Execution.createCriteria().list {
            gt('dateStarted', today)
            inList('project', projectNames)
            projections {
                groupProperty('project')
                count()
            }
        }
        proj2=System.currentTimeMillis()-proj2
        def execCount= 0 //Execution.countByDateStartedGreaterThan( today)
        projects2.each{val->
            if(val.size()==2){
                if(summary[val[0]]) {
                    summary[val[0].toString()].execCount = val[1]
                    projects << val[0]
                    execCount+=val[1]
                }
            }
        }
        def totalFailedCount= 0
        def failedExecs = Execution.createCriteria().list {
            gt('dateStarted', today)
            inList('status', ['false', 'failed'])
            inList('project', projectNames)
            projections {
                groupProperty('project')
                count()
            }
        }
        failedExecs.each{val->
            if(val.size()==2){
                if(summary[val[0]]) {
                    summary[val[0].toString()].failedCount = val[1]
                    totalFailedCount+=val[1]
                }
            }
        }

        long proj3=System.currentTimeMillis()
        def users2 = Execution.createCriteria().list {
            gt('dateStarted', today)
            inList('project', projectNames)
            projections {
                distinct('user')
                property('project')
            }
        }
        proj3=System.currentTimeMillis()-proj3
        def users = new HashSet<String>()
        users2.each{val->
            if(val.size()==2){
                if(summary[val[1]]){
                    summary[val[1]].userSummary<<val[0]
                    summary[val[1]].userCount=summary[val[1]].userSummary.size()
                    users.add(val[0])
                }
            }
        }

        log.debug("loadSummaryProjectStats... ${System.currentTimeMillis()-start}, proj2 ${proj2}, proj3 ${proj3}")
        [summary:summary,recentUsers:users,recentProjects:projects,execCount:execCount,totalFailedCount:totalFailedCount]
    }

    def projectNamesAjax() {
        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)
        long start = System.currentTimeMillis()
        def fprojects = frameworkService.projectNames(authContext)
        session.frameworkProjects = fprojects
        log.debug("frameworkService.projectNames(context)... ${System.currentTimeMillis() - start}")

        render(contentType:'application/json',text:
                ([projectNames: fprojects] )as JSON
        )
    }
    def homeAjax(BaseQuery paging){
        if('true'!=request.getHeader('x-rundeck-ajax')) {
            return redirect(action: 'home')
        }
        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)
        long start=System.currentTimeMillis()
        //select paged projects to return
        def fprojects
        List<String> projectNames = frameworkService.projectNames(authContext)
        def pagingparams=[:]
        if (null != paging.max && null != paging.offset) {
            if (paging.max > 0 && paging.offset < projectNames.size()) {

                def lastIndex = Math.min(projectNames.size() - 1, paging.offset + paging.max - 1)
                pagingparams = [max: paging.max, offset: paging.offset]
                if (lastIndex + 1 < projectNames.size()) {
                    pagingparams.nextoffset = lastIndex + 1
                } else {
                    pagingparams.nextoffset = -1
                }
                fprojects = projectNames[paging.offset..lastIndex].collect {
                    frameworkService.getFrameworkProject(it)
                }
            } else {
                fprojects = []
            }
        }else if (params.projects) {
            def selected = [params.projects].flatten()
            if(selected.size()==1 && selected[0].contains(',')){
                selected = selected[0].split(',') as List
            }
            fprojects = selected.findAll{projectNames.contains(it)}.collect{
                frameworkService.getFrameworkProject(it)
            }
        } else {
            fprojects = frameworkService.projects(authContext)
        }

        log.debug("frameworkService.projects(context)... ${System.currentTimeMillis()-start}")
        start=System.currentTimeMillis()


        def allsummary=cachedSummaryProjectStats(projectNames).summary
        def summary=[:]
        def durs=[]
        fprojects.each { IRundeckProject project->
            long sumstart=System.currentTimeMillis()
            summary[project.name]=allsummary[project.name]?:[:]

            summary[project.name].description= project.hasProperty("project.description")?project.getProperty("project.description"):''
            if(!params.refresh) {
                summary[project.name].readmeDisplay = menuService.getReadmeDisplay(project)
                summary[project.name].motdDisplay = menuService.getMotdDisplay(project)
                summary[project.name].readme = frameworkService.getFrameworkProjectReadmeContents(project)
                //authorization
                summary[project.name].auth = [
                        jobCreate: frameworkService.authorizeProjectResource(authContext, AuthConstants.RESOURCE_TYPE_JOB,
                                AuthConstants.ACTION_CREATE, project.name),
                        admin: frameworkService.authorizeApplicationResourceAny(authContext,
                                                                                frameworkService.authResourceForProject(project.name),
                                [AuthConstants.ACTION_CONFIGURE, AuthConstants.ACTION_ADMIN, AuthConstants.ACTION_IMPORT,
                                        AuthConstants.ACTION_EXPORT, AuthConstants.ACTION_DELETE]),
                ]
            }
            durs<<(System.currentTimeMillis()-sumstart)
        }


        if(durs.size()>0) {
            def sum=durs.inject(0) { a, b -> a + b }
            log.debug("summarize avg/proj (${durs.size()}) ${sum}ms ... ${sum / durs.size()}")
        }
        render(contentType:'application/json',text:
                (pagingparams + [
                        projects : summary.values(),
                ] )as JSON
        )
    }
    def homeSummaryAjax(){
        if('true'!=request.getHeader('x-rundeck-ajax')) {
            return redirect(action: 'home')
        }
        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)
        Framework framework = frameworkService.rundeckFramework
        long start=System.currentTimeMillis()
        //select paged projects to return
        List<String> projectNames = frameworkService.projectNames(authContext)

        log.debug("frameworkService.homeSummaryAjax(context)... ${System.currentTimeMillis()-start}")
        start=System.currentTimeMillis()
        def allsummary=cachedSummaryProjectStats(projectNames)
        def projects=allsummary.recentProjects
        def users=allsummary.recentUsers
        def execCount=allsummary.execCount
        def totalFailedCount=allsummary.totalFailedCount

        def fwkNode = framework.getFrameworkNodeName()

        render(contentType: 'application/json', text:
                ([
                        execCount        : execCount,
                        totalFailedCount : totalFailedCount,
                        recentUsers      : users,
                        recentProjects   : projects,
                        frameworkNodeName: fwkNode
                ]) as JSON
        )
    }

    /**
    * API Actions
     */

    def apiLogstorageInfo() {
        if (!apiService.requireVersion(request, response, ApiRequestFilters.V17)) {
            return
        }
        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)

        if (!apiService.requireAuthorized(
                frameworkService.authorizeApplicationResource(
                        authContext,
                        AuthConstants.RESOURCE_TYPE_SYSTEM,
                        AuthConstants.ACTION_READ
                ),
                response,
                [AuthConstants.ACTION_READ, 'System','Logstorage Info'].toArray()
        )) {
            return
        }

        def data = logFileStorageService.getStorageStats()
        def propnames = [
                'succeededCount',
                'failedCount',
                'queuedCount',
                'totalCount',
                'incompleteCount',
                'missingCount'
        ]
        withFormat {
            json {
                apiService.renderSuccessJson(response) {
                    enabled = data.pluginName ? true : false
                    pluginName = data.pluginName
                    for (String name : propnames) {
                        delegate.setProperty(name, data[name])
                    }
                }
            }
            xml {

                apiService.renderSuccessXml(request, response) {
                    delegate.'logStorage'(enabled: data.pluginName ? true : false, pluginName: data.pluginName) {
                        for (String name : propnames) {
                            delegate."${name}"(data[name])
                        }
                    }
                }
            }
        }
    }

    def apiLogstorageListIncompleteExecutions(BaseQuery query) {
        if (!apiService.requireVersion(request, response, ApiRequestFilters.V17)) {
            return
        }
        query.validate()
        if (query.hasErrors()) {
            return apiService.renderErrorFormat(
                    response,
                    [
                            status: HttpServletResponse.SC_BAD_REQUEST,
                            code: "api.error.parameter.error",
                            args: [query.errors.allErrors.collect { message(error: it) }.join("; ")]
                    ]
            )
        }

        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)

        if (!apiService.requireAuthorized(
                frameworkService.authorizeApplicationResource(
                        authContext,
                        AuthConstants.RESOURCE_TYPE_SYSTEM,
                        AuthConstants.ACTION_READ
                ),
                response,
                [AuthConstants.ACTION_READ, 'System','Logstorage Info'].toArray()
        )) {
            return
        }


        def total=logFileStorageService.countIncompleteLogStorageRequests()
        def list = logFileStorageService.listIncompleteRequests(
                frameworkService.serverUUID,
                [max: query.max?:20, offset: query.offset?:0]
        )
        def queuedIds=logFileStorageService.getQueuedIncompleteRequestIds()
        def failedIds=logFileStorageService.getFailedRequestIds()
        withFormat{
            json{
                apiService.renderSuccessJson(response) {
                    delegate.'total' = total
                    max = query.max ?: 20
                    offset = query.offset ?: 0
                    executions = list.collect { LogFileStorageRequest req ->
                        def data=exportRequestMap(
                                req,
                                queuedIds.contains(req.id),
                                failedIds.contains(req.id),
                                failedIds.contains(req.id) ? logFileStorageService.getFailures(req.id) : null
                        )
                        [
                                id:data.executionId,
                                project:data.project,
                                href:data.href,
                                permalink:data.permalink,
                                storage:[
                                        localFilesPresent:data.localFilesPresent,
                                        incompleteFiletypes:data.filetype,
                                        queued:data.queued,
                                        failed:data.failed,
                                        date:apiService.w3cDateValue(req.dateCreated),
                                ],
                                errors:data.messages
                        ]
                    }
                }
            }
            xml{
                apiService.renderSuccessXml (request,response) {
                    logstorage {
                        incompleteExecutions(total: total, max: query.max ?: 20, offset: query.offset ?: 0) {
                            list.each { LogFileStorageRequest req ->
                                def data=exportRequestMap(
                                        req,
                                        queuedIds.contains(req.id),
                                        failedIds.contains(req.id),
                                        failedIds.contains(req.id) ? logFileStorageService.getFailures(req.id) : null
                                )
                                execution(id:data.executionId,project:data.project,href:data.href,permalink:data.permalink){
                                    delegate.'storage'(
                                            incompleteFiletypes:data.filetype,
                                            queued:data.queued,
                                            failed:data.failed,
                                            date: apiService.w3cDateValue(req.dateCreated),
                                            localFilesPresent:data.localFilesPresent,
                                    ) {
                                        if(data.messages){
                                            delegate.'errors' {
                                                data.messages.each {
                                                    delegate.'message'(it)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    def apiResumeIncompleteLogstorage() {
        if (!apiService.requireVersion(request, response, ApiRequestFilters.V17)) {
            return
        }
        AuthContext authContext = frameworkService.getAuthContextForSubject(session.subject)

        if (!apiService.requireAuthorized(
                frameworkService.authorizeApplicationResource(
                        authContext,
                        AuthConstants.RESOURCE_TYPE_SYSTEM,
                        AuthConstants.ACTION_ADMIN
                ),
                response,
                [AuthConstants.ACTION_ADMIN, 'System','Logstorage'].toArray()
        )) {
            return
        }

        logFileStorageService.resumeIncompleteLogStorageAsync(frameworkService.serverUUID)
        withFormat {
            json {
                apiService.renderSuccessJson(response) {
                    resumed=true
                }
            }
            xml {

                apiService.renderSuccessXml(request, response) {
                    delegate.'logStorage'(resumed:true)
                }
            }
        }
    }


    /**
     * API: /api/jobs, version 1
     */
    def apiJobsList (ScheduledExecutionQuery query){
        if (!apiService.requireApi(request, response)) {
            return
        }
        if(!params.project){
            return apiService.renderErrorFormat(response, [status: HttpServletResponse.SC_BAD_REQUEST,
                    code: 'api.error.parameter.required', args: ['project']])

        }

        query.projFilter = params.project
        //test valid project

        def exists=frameworkService.existsFrameworkProject(params.project)
        if(!exists){
            return apiService.renderErrorFormat(response, [status: HttpServletResponse.SC_NOT_FOUND,
                    code: 'api.error.item.doesnotexist', args: ['project', params.project]])

        }
        if(query.groupPathExact || query.jobExactFilter){
            //these query inputs require API version 2
            if (!apiService.requireVersion(request,response,ApiRequestFilters.V2)) {
                return
            }
        }
        if(null!=query.scheduledFilter || null!=query.serverNodeUUIDFilter){
            if (!apiService.requireVersion(request,response,ApiRequestFilters.V17)) {
                return
            }
        }
        if(null!=query.scheduleEnabledFilter || null!=query.executionEnabledFilter){
            if (!apiService.requireVersion(request,response,ApiRequestFilters.V18)) {
                return
            }
        }

        if (request.api_version < ApiRequestFilters.V14 && !(response.format in ['all','xml'])) {
            return apiService.renderErrorFormat(response,[
                    status:HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
                    code: 'api.error.item.unsupported-format',
                    args: [response.format]
            ])
        }
        if(query.hasErrors()){
            return apiService.renderErrorFormat(response,
                                                [
                                                        status: HttpServletResponse.SC_BAD_REQUEST,
                                                        code: "api.error.parameter.error",
                                                        args: [query.errors.allErrors.collect { message(error: it) }.join("; ")]
                                                ])
        }
        def results = jobsFragment(query)

        respondApiJobsList(results.nextScheduled)
    }

    /**
     * API: get job info: /api/18/job/{id}/info
     */
    def apiJobDetail() {
        if (!apiService.requireVersion(request, response, ApiRequestFilters.V18)) {
            return
        }

        if (!apiService.requireParameters(params, response, ['id'])) {
            return
        }

        def ScheduledExecution scheduledExecution = scheduledExecutionService.getByIDorUUID(params.id)

        if (!apiService.requireExists(response, scheduledExecution, ['Job ID', params.id])) {
            return
        }
        AuthContext authContext = frameworkService.getAuthContextForSubjectAndProject(
                session.subject,
                scheduledExecution.project
        )
        if (!frameworkService.authorizeProjectJobAll(
                authContext,
                scheduledExecution,
                [AuthConstants.ACTION_READ],
                scheduledExecution.project
        )) {
            return apiService.renderErrorXml(
                    response,
                    [
                            status: HttpServletResponse.SC_FORBIDDEN,
                            code  : 'api.error.item.unauthorized',
                            args  : ['Read', 'Job ID', params.id]
                    ]
            )
        }
        if (!(response.format in ['all', 'xml', 'json'])) {
            return apiService.renderErrorXml(
                    response,
                    [
                            status: HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
                            code  : 'api.error.item.unsupported-format',
                            args  : [response.format]
                    ]
            )
        }
        def extra = [:]
        def clusterModeEnabled = frameworkService.isClusterModeEnabled()
        def serverNodeUUID = frameworkService.serverUUID
        if (clusterModeEnabled && scheduledExecution.scheduled) {
            extra.serverNodeUUID = scheduledExecution.serverNodeUUID
            extra.serverOwner = scheduledExecution.serverNodeUUID == serverNodeUUID
        }
        if (scheduledExecution.totalTime >= 0 && scheduledExecution.execCount > 0) {
            def long avg = Math.floor(scheduledExecution.totalTime / scheduledExecution.execCount)
            extra.averageDuration = avg
        }
        if(scheduledExecution.shouldScheduleExecution()){
            extra.nextScheduledExecution=scheduledExecutionService.nextExecutionTime(scheduledExecution)
        }
        respond(

                JobInfo.from(
                        scheduledExecution,
                        apiService.apiHrefForJob(scheduledExecution),
                        apiService.guiHrefForJob(scheduledExecution),
                        extra
                ),

                [formats: ['xml', 'json']]
        )
    }

    private void respondApiJobsList(List<ScheduledExecution> results) {
        def clusterModeEnabled = frameworkService.isClusterModeEnabled()
        def serverNodeUUID = frameworkService.serverUUID

        if (request.api_version >= ApiRequestFilters.V18) {
            //new response format mechanism
            //no <result> tag
            def data = new JobInfoList(
                    results.collect { ScheduledExecution se ->
                        Map data = [:]
                        if (clusterModeEnabled) {
                            data = [
                                    serverNodeUUID: se.serverNodeUUID,
                                    serverOwner   : se.serverNodeUUID == serverNodeUUID
                            ]
                        }
                        JobInfo.from(
                                se,
                                apiService.apiHrefForJob(se),
                                apiService.guiHrefForJob(se),
                                data
                        )
                    }
            )
            respond(
                    data,
                    [formats: ['xml', 'json']]
            )
            return
        }
        withFormat {
            def xmlresponse= {
                return apiService.renderSuccessXml(request, response) {
                    delegate.'jobs'(count: results.size()) {
                        results.each { ScheduledExecution se ->
                            def jobparams = [id: se.extid, href: apiService.apiHrefForJob(se),
                                             permalink: apiService.guiHrefForJob(se)]
                            if (request.api_version >= ApiRequestFilters.V17) {
                                jobparams.scheduled = se.scheduled
                                jobparams.scheduleEnabled = se.scheduleEnabled
                                jobparams.enabled = se.executionEnabled
                                if (clusterModeEnabled && se.scheduled) {
                                    jobparams.serverNodeUUID = se.serverNodeUUID
                                    jobparams.serverOwner = jobparams.serverNodeUUID == serverNodeUUID
                                }
                            }
                            job(jobparams) {
                                name(se.jobName)
                                group(se.groupPath)
                                project(se.project)
                                description(se.description)
                            }
                        }
                    }
                }
            }
            xml xmlresponse
            json {
                return apiService.renderSuccessJson(response) {
                    results.each { ScheduledExecution se ->
                        def jobparams = [id         : se.extid,
                                         name       : (se.jobName),
                                         group      : (se.groupPath),
                                         project    : (se.project),
                                         description: (se.description),
                                         href       : apiService.apiHrefForJob(se),
                                         permalink  : apiService.guiHrefForJob(se)]
                        if (request.api_version >= ApiRequestFilters.V17) {
                            jobparams.scheduled = se.scheduled
                            jobparams.scheduleEnabled = se.scheduleEnabled
                            jobparams.enabled = se.executionEnabled
                            if (clusterModeEnabled && se.scheduled) {
                                jobparams.serverNodeUUID = se.serverNodeUUID
                                jobparams.serverOwner = jobparams.serverNodeUUID == serverNodeUUID
                            }
                        }
                        element(jobparams)
                    }
                }
            }
            '*' xmlresponse
        }
    }
    /**
     * Require server UUID and list all owned jobs
     * /api/$api_version/scheduler/server/$uuid/jobs and
     * /api/$api_version/scheduler/jobs
     * @return
     */
    def apiSchedulerListJobs(String uuid, boolean currentServer) {
        if (!apiService.requireVersion(request, response, ApiRequestFilters.V17)) {
            return
        }
        if(currentServer) {
            uuid = frameworkService.serverUUID
        }
        def query = new ScheduledExecutionQuery(serverNodeUUIDFilter: uuid, scheduledFilter: true)
        query.validate()
        if (query.hasErrors()) {
            return apiService.renderErrorFormat(
                    response,
                    [
                            status: HttpServletResponse.SC_BAD_REQUEST,
                            code: "api.error.parameter.error",
                            args: [query.errors.allErrors.collect { message(error: it) }.join("; ")]
                    ]
            )
        }


        def list = ScheduledExecution.findAllByServerNodeUUID(uuid)
        //filter authorized jobs
        Map<String, UserAndRolesAuthContext> projectAuths = [:]
        def authForProject = { String project ->
            if (projectAuths[project]) {
                return projectAuths[project]
            }
            projectAuths[project] = frameworkService.getAuthContextForSubjectAndProject(session.subject, project)
            projectAuths[project]
        }
        def authorized = list.findAll { ScheduledExecution se ->
            frameworkService.authorizeProjectJobAll(
                    authForProject(se.project),
                    se,
                    [AuthConstants.ACTION_READ],
                    se.project
            )
        }

        respondApiJobsList(authorized)
    }
    /**
     * API: /api/2/project/NAME/jobs, version 2
     */
    def apiJobsListv2 (ScheduledExecutionQuery query) {
        if(!apiService.requireVersion(request,response,ApiRequestFilters.V2)){
            return
        }
        return apiJobsList(query)
    }

    /**
     * API: /api/14/project/NAME/jobs/export
     */
    def apiJobsExportv14 (ScheduledExecutionQuery query){
        if(!apiService.requireVersion(request,response,ApiRequestFilters.V14)){
            return
        }
        return apiJobsExport(query)
    }
    /**
     * API: /jobs/export, version 1, deprecated since v14
     */
    def apiJobsExport (ScheduledExecutionQuery query){
        if (!apiService.requireApi(request, response)) {
            return
        }
        if(!params.project){
            return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_BAD_REQUEST,
                    code: 'api.error.parameter.required', args: ['project']])
        }
        query.projFilter = params.project
        //test valid project
        Framework framework = frameworkService.getRundeckFramework()

        def exists=frameworkService.existsFrameworkProject(params.project)
        if(!exists){
            return apiService.renderErrorXml(response, [status: HttpServletResponse.SC_NOT_FOUND,
                    code: 'api.error.item.doesnotexist', args: ['project',params.project]])
        }
        //don't load scm status for api response
        params['_no_scm']=true
        def results = jobsFragment(query)

        withFormat{
            xml{
                def writer = new StringWriter()
                def xml = new MarkupBuilder(writer)
                JobsXMLCodec.encodeWithBuilder(results.nextScheduled,xml)
                writer.flush()
                render(text:writer.toString(),contentType:"text/xml",encoding:"UTF-8")
            }
            yaml{
                final def encoded = JobsYAMLCodec.encode(results.nextScheduled as List)
                render(text:encoded,contentType:"text/yaml",encoding:"UTF-8")
            }
        }
    }

    /**
     * API: /project/PROJECT/executions/running, version 14
     */
    def apiExecutionsRunningv14 (){
        if(!apiService.requireVersion(request,response,ApiRequestFilters.V14)){
            return
        }
        return apiExecutionsRunning()
    }

    /**
     * API: /executions/running, version 1
     */
    def apiExecutionsRunning (){
        if (!apiService.requireApi(request, response)) {
            return
        }
        if(!params.project){
            return apiService.renderErrorFormat(response, [status: HttpServletResponse.SC_BAD_REQUEST,
                    code: 'api.error.parameter.required', args: ['project']])
        }
        //test valid project
        Framework framework = frameworkService.getRundeckFramework()

        //allow project='*' to indicate all projects
        def allProjects = request.api_version >= ApiRequestFilters.V9 && params.project == '*'
        if(!allProjects){
            if(!frameworkService.existsFrameworkProject(params.project)){
                return apiService.renderErrorFormat(response, [status: HttpServletResponse.SC_NOT_FOUND,
                        code: 'api.error.parameter.doesnotexist', args: ['project',params.project]])
            }
        }
        if (request.api_version < ApiRequestFilters.V14 && !(response.format in ['all','xml'])) {
            return apiService.renderErrorXml(response,[
                    status:HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
                    code: 'api.error.item.unsupported-format',
                    args: [response.format]
            ])
        }

        QueueQuery query = new QueueQuery(runningFilter:'running',projFilter:params.project)
        if(params.max){
            query.max=params.int('max')
        }
        if(params.offset){
            query.offset=params.int('offset')
        }
        def results = nowrunning(query)

        withFormat{
            xml {
                return executionService.respondExecutionsXml(
                        request,
                        response,
                        results.nowrunning,
                        [
                                total: results.total,
                                offset: results.offset,
                                max: results.max
                        ]
                )
            }
            json {
                return executionService.respondExecutionsJson(
                        request,
                        response,
                        results.nowrunning,
                        [
                                total: results.total,
                                offset: results.offset,
                                max: results.max
                        ]
                )
            }
        }

    }
}

