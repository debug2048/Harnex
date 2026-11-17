export interface paths {
    "/api/cron": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["cronList"];
        put?: never;
        post: operations["cronAdd"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/cron/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete: operations["cronDel"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/cron/{id}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["cancelExecution"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/cron/{id}/enabled": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["cronEnabled"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/cron/{id}/history": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["history_1"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/cron/{id}/run": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["cronRun"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/cron/preview": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["cronPreview"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/cron/results/{attemptId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["result"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/cron/targets": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["targets"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/cron/targets/{targetId}/notifications": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["notificationSettings"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/cron/toolsets": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["toolsets"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/im": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["view"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/im/{channel}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put: operations["save"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/im/{channel}/reconnect": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["reconnect"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/im/{channel}/secret": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["revealSecret"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/im/credentials/{id}/secret": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["revealSavedSecret"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/im/dingtalk/template": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["template"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/im/notifications": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["notifications_1"];
        put: operations["notifications"];
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/im/pairing/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["pair"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/agent/conversations/{id}/snapshots": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["list_7"];
        put?: never;
        post: operations["take"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/agent/conversations/{id}/snapshots/{seq}/branch": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["branch_1"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/agent/conversations/{id}/snapshots/{seq}/restore": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["restore"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/agent/conversations/{id}/snapshots/{seq}/restore-plan": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["restorePlan"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/artifacts/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["metadata"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/artifacts/{id}/content": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["content"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/artifacts/{id}/exports": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["export_1"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/artifacts/{id}/project-exports": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["export"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/artifacts/imports": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["upload"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/automation-executions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["create_3"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/automation-executions/{attemptId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_7"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/automation-executions/{attemptId}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["cancel_2"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/conversations": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["list_5"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/conversations/{conversationId}/submissions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["list_6"];
        put?: never;
        post: operations["enqueue_1"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/conversations/{conversationId}/submissions/{submissionId}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["cancel_1"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/conversations/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_6"];
        put?: never;
        post?: never;
        delete: operations["delete"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/conversations/{id}/branches/selection": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["branch"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/conversations/{id}/execution-records/{recordId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["executionRecord"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/conversations/{id}/facts": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["facts"];
        put?: never;
        post: operations["registerFact"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/conversations/{id}/forks": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["fork"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/conversations/{id}/messages": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["messages"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/conversations/{id}/messages/{messageId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["message"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/conversations/{id}/messages/{messageId}/retry": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["retry"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/conversations/{id}/messages/{messageId}/revisions": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["revise"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/conversations/{id}/title": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["title"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/conversations/{id}/turns/{turnId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["turn"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/conversations/search": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["search"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/goals": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["list_4"];
        put?: never;
        post: operations["create_2"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/goals/{goalId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_5"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch: operations["patch"];
        trace?: never;
    };
    "/api/v1/goals/{goalId}/history": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["history"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/goals/{goalId}/runs": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["startRun"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/image-operations/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["operation"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/image-services": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["catalog"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/previews": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["create_1"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/previews/{id}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete: operations["remove"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/protocol": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["protocol"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["list"];
        put?: never;
        post: operations["create"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{runId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{runId}/cancel": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["cancel"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{runId}/context-plans": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["list_3"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{runId}/context-plans/{planId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_4"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{runId}/events": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["events"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{runId}/inputs": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["list_2"];
        put?: never;
        post: operations["enqueue"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{runId}/inputs/{inputId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post?: never;
        delete: operations["withdraw"];
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{runId}/model-calls": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["list_1"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{runId}/model-calls/{callId}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_3"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{runId}/model-calls/status": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["status"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{runId}/pause": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["pause"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{runId}/presentation": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_2"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{runId}/resume": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["resume"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{runId}/review": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get?: never;
        put?: never;
        post: operations["review"];
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{runId}/subagents/{threadId}/result": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["subagentResult"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{runId}/tools/{seq}": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["get_1"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{runId}/usage": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["report"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/runs/{runId}/usage/entries": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["entries"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
    "/api/v1/scope": {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        get: operations["scope"];
        put?: never;
        post?: never;
        delete?: never;
        options?: never;
        head?: never;
        patch?: never;
        trace?: never;
    };
}
export type webhooks = Record<string, never>;
export interface components {
    schemas: {
        AgentExecutionOptions: {
            interactionTarget?: string;
            limits?: components["schemas"]["StepLimits"];
            permissions?: components["schemas"]["PermissionContext"];
            /** @enum {string} */
            planReview?: "REQUIRED" | "AUTOMATIC";
        };
        AgentSpec: {
            ideContext?: string;
            interactionTarget?: string;
            /**
             * @description discriminator enum property added by openapi-typescript
             * @enum {string}
             */
            kind: "AGENT";
            limits?: components["schemas"]["Limits"];
            permissionMode?: string;
            /** @enum {string} */
            planReview?: "REQUIRED" | "AUTOMATIC";
            readOnly?: boolean;
            text?: components["schemas"]["TextTarget"];
            toolsets?: string[];
        };
        ArtifactRecord: {
            artifactId: string;
            /** @enum {string} */
            availability: "LOCAL" | "UNAVAILABLE";
            /** Format: int64 */
            bytes: number;
            checksum: string;
            /** Format: int64 */
            createdAt: number;
            creator: components["schemas"]["Creator"];
            /** Format: int32 */
            height?: number;
            mime: string;
            name: string;
            /** Format: int32 */
            schemaVersion: number;
            scopeId: string;
            sourceRefs: string[];
            /** Format: int32 */
            width?: number;
        };
        ArtifactUpdate: {
            artifactId?: string;
            /** Format: int64 */
            revision?: number;
        };
        Attempt: {
            /** @enum {string} */
            acceptance?: "NOT_SENT" | "SENT_UNKNOWN" | "ACCEPTED" | "REJECTED" | "OUTPUT_RECEIVED" | "UNKNOWN";
            attemptId?: string;
            contextPlanId?: string;
            errorCode?: string;
            /** Format: int64 */
            finishedAt?: number;
            /** Format: int64 */
            firstOutputAfterMillis?: number;
            /** Format: int32 */
            index?: number;
            model?: string;
            protocol?: string;
            provider?: string;
            /** Format: int64 */
            retryAfterMillis?: number;
            /** Format: int64 */
            startedAt?: number;
            /** @enum {string} */
            state?: "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED" | "UNKNOWN";
            timings?: {
                [key: string]: number;
            };
            upstreamRequestId?: string;
            usage?: components["schemas"]["ProviderTokenUsage"];
        };
        AutomationAttempt: {
            cancelRequested?: boolean;
            conversationId?: string;
            error?: string;
            /** Format: int64 */
            finishedAt?: number;
            id?: string;
            jobId?: string;
            name?: string;
            /** Format: int64 */
            ownerPid?: number;
            /** Format: int64 */
            ownerStartedAt?: number;
            runId?: string;
            /** Format: int64 */
            startedAt?: number;
            status?: string;
            target?: components["schemas"]["AutomationTarget"];
            task?: components["schemas"]["CronJob"];
            trigger?: string;
        };
        AutomationChange: {
            /** Format: int32 */
            added?: number;
            path?: string;
            /** Format: int32 */
            removed?: number;
            status?: string;
        };
        AutomationExecutionRequest: {
            allowMutations?: boolean;
            attemptId?: string;
            jobId?: string;
            /** Format: int32 */
            maxWorkIterations?: number;
            model?: string;
            /** @enum {string} */
            notificationMode?: "OFF" | "ALL" | "FAILURES";
            /** @enum {string} */
            planReview?: "REQUIRED" | "AUTOMATIC";
            /** @enum {string} */
            profile?: "PERSONAL" | "PROJECT";
            prompt?: string;
            provider?: string;
            toolsets?: string[];
        };
        AutomationExecutionResult: {
            changes?: components["schemas"]["AutomationChange"][];
            conversationId?: string;
            error?: string;
            /** Format: int64 */
            finishedAt?: number;
            output?: string;
            runId?: string;
            /** Format: int64 */
            startedAt?: number;
            status?: string;
            terminal?: boolean;
            usage?: components["schemas"]["UsageSummary"];
        };
        AutomationExecutionStart: {
            code?: string;
            conversationId?: string;
            message?: string;
            runId?: string;
        };
        AutomationTarget: {
            connectionId?: string;
            id?: string;
            label?: string;
            /** @enum {string} */
            profile?: "PERSONAL" | "PROJECT";
            root?: string;
            scopeId?: string;
        };
        AutomationTargetView: {
            online?: boolean;
            target?: components["schemas"]["AutomationTarget"];
        };
        Branch: {
            commandId?: string;
            /** Format: int64 */
            expectedHistoryRevision?: number;
            leafId?: string;
        };
        Catalog: {
            defaultServiceId?: string;
            services?: components["schemas"]["ServiceView"][];
            /** @enum {string} */
            state?: "UNCONFIGURED" | "UNSUPPORTED" | "CONFIGURED_UNVERIFIED" | "AVAILABLE" | "TEMPORARILY_UNAVAILABLE";
        };
        CommitReceipt: {
            conversationId?: string;
            /** Format: int64 */
            historyRevision?: number;
            /** @enum {string} */
            kind?: "TURN_ACCEPTED" | "ANSWER_CHECKPOINT" | "AGENT_STEP_COMMITTED" | "RESULT_COMMITTED" | "MESSAGE_REVISED" | "BRANCH_SELECTED" | "CONVERSATION_FORKED" | "METADATA_UPDATED" | "CONVERSATION_DELETED" | "EXECUTION_RECORD_COMMITTED" | "RUN_STATE_UPDATED" | "RUN_INPUT_COMMITTED";
            messageIds?: string[];
            operationId?: string;
            runId?: string;
            turnId?: string;
        };
        CompletionRequest: {
            evidence?: {
                [key: string]: unknown;
            }[];
            note?: string;
            status?: string;
            /** Format: int64 */
            submittedAt?: number;
        };
        ContentPart: {
            artifactId?: string;
            imageOperationId?: string;
            text?: string;
            /** @enum {string} */
            type?: "TEXT" | "ARTIFACT_REF" | "IMAGE_OPERATION_REF";
        };
        ContextBlock: {
            contentHash: string;
            /** Format: int64 */
            estimatedTokens: number;
            included: boolean;
            omissionReason?: string;
            sourceKind: string;
            sourceRef: string;
        };
        ContextPlan: {
            attemptId?: string;
            blocks: components["schemas"]["ContextBlock"][];
            callId?: string;
            /** Format: int64 */
            capturedAt?: number;
            contextPlanId: string;
            /** Format: int64 */
            estimatedTokens: number;
            estimateMethod: string;
            /** Format: int32 */
            inputBudget: number;
            leafId?: string;
            model: string;
            /** Format: int32 */
            outputReserve: number;
            policyVersion: string;
            provider: string;
            runId: string;
            /** Format: int32 */
            safetyReserve: number;
            sourceMessageIds: string[];
            summaryId?: string;
            truncated: boolean;
        };
        Control: {
            clientRequestId?: string;
            interactionTarget?: string;
            /** Format: int64 */
            revision?: number;
        };
        ConversationMeta: {
            activeLeafId?: string;
            conversationId: string;
            /** Format: int64 */
            createdAt: number;
            defaults?: components["schemas"]["AgentSpec"] | components["schemas"]["ConversationSpec"];
            deleted: boolean;
            /** Format: int64 */
            historyRevision: number;
            latestRunId?: string;
            latestRunStatus?: string;
            /** Format: int64 */
            messageCount: number;
            /** Format: int64 */
            metadataRevision: number;
            preview?: string;
            /** Format: int32 */
            schemaVersion: number;
            scopeId: string;
            title: string;
            /** Format: int64 */
            updatedAt: number;
        };
        ConversationSpec: {
            /** @enum {string} */
            action: "TEXT" | "AUTO" | "IMAGE_GENERATE" | "IMAGE_EDIT";
            image?: components["schemas"]["ImageSpec"];
            imageServiceId?: string;
            /**
             * @description discriminator enum property added by openapi-typescript
             * @enum {string}
             */
            kind: "CONVERSATION";
            text?: components["schemas"]["TextTarget"];
        };
        CostView: {
            /** Format: double */
            amount?: number;
            /** Format: int64 */
            calculatedAt?: number;
            known?: boolean;
            model?: string;
            provider?: string;
        };
        Creator: {
            kind?: string;
            operationId?: string;
            runId?: string;
        };
        CriterionResult: {
            /** Format: int64 */
            checkedAt?: number;
            criterionId?: string;
            detail?: string;
            evidence?: {
                [key: string]: unknown;
            };
            required?: boolean;
            /** @enum {string} */
            status?: "PASS" | "FAIL" | "REVIEW_REQUIRED";
            type?: string;
        };
        CronJob: {
            allowMutations?: boolean;
            cron?: string;
            disabled?: boolean;
            /** Format: int64 */
            failureCount?: number;
            id?: string;
            /** Format: int64 */
            lastFailureAt?: number;
            lastFailureReason?: string;
            /** Format: int64 */
            lastRunAt?: number;
            lastRunId?: string;
            /** Format: int64 */
            lastSkipAt?: number;
            lastStatus?: string;
            /** Format: int32 */
            maxWorkIterations?: number;
            model?: string;
            name?: string;
            /** @enum {string} */
            notificationMode?: "OFF" | "ALL" | "FAILURES";
            /** @enum {string} */
            planReview?: "REQUIRED" | "AUTOMATIC";
            prompt?: string;
            provider?: string;
            /** Format: int64 */
            runCount?: number;
            /** Format: int64 */
            skipCount?: number;
            targetId?: string;
            timeZone?: string;
            toolsets?: string[];
        };
        CronJobView: {
            allowMutations?: boolean;
            conversationId?: string;
            cron?: string;
            disabled?: boolean;
            /** Format: int64 */
            failureCount?: number;
            id?: string;
            /** Format: int64 */
            lastFailureAt?: number;
            lastFailureReason?: string;
            /** Format: int64 */
            lastRunAt?: number;
            lastRunId?: string;
            /** Format: int64 */
            lastSkipAt?: number;
            lastStatus?: string;
            /** Format: int32 */
            maxWorkIterations?: number;
            model?: string;
            name?: string;
            /** Format: int64 */
            nextRunAt?: number;
            /** @enum {string} */
            notificationMode?: "OFF" | "ALL" | "FAILURES";
            pending?: boolean;
            persistenceError?: string;
            /** @enum {string} */
            planReview?: "REQUIRED" | "AUTOMATIC";
            prompt?: string;
            provider?: string;
            /** Format: int64 */
            runCount?: number;
            running?: boolean;
            /** Format: int64 */
            skipCount?: number;
            state?: string;
            target?: components["schemas"]["AutomationTarget"];
            targetOnline?: boolean;
            timeZone?: string;
            toolsets?: string[];
            waitingReason?: string;
        };
        Delete: {
            commandId?: string;
            /** Format: int64 */
            expectedHistoryRevision?: number;
        };
        DiagnosticStatus: {
            /** Format: int64 */
            droppedWrites?: number;
        };
        EnabledRequest: {
            enabled?: boolean;
        };
        ExecutionConfig: {
            permissionMode?: string;
            toolsets?: string[];
        };
        ExecutionSearchRecord: {
            conversationId?: string;
            deleted?: boolean;
            recordId?: string;
            runId?: string;
            sourceMessageId?: string | null;
            /** Format: int64 */
            sourceRevision?: number;
            text?: string;
            title?: string;
            toolCallId?: string;
        };
        ExecutionSpec: components["schemas"]["AgentSpec"] | components["schemas"]["ConversationSpec"];
        Export: {
            artifactId?: string;
            downloadPath?: string;
            filename?: string;
        };
        Fact: {
            /** Format: int64 */
            createdAt: number;
            id: string;
            key: string;
            messageId: string;
            qualifier: string;
            quote: string;
            /** @enum {string} */
            registration?: "explicit" | "summary_candidate";
            /** Format: int64 */
            revision: number;
            supersedes?: string | null;
        };
        FactPage: {
            items: components["schemas"]["Fact"][];
            /** Format: int32 */
            nextOffset?: number | null;
            /** Format: int32 */
            total: number;
        };
        FileSnapshotEntry: {
            afterExisted?: boolean;
            afterHash?: string;
            /** Format: int64 */
            afterSize?: number;
            agentId?: string;
            beforeHash?: string;
            /** Format: int64 */
            beforeMtime?: number;
            /** Format: int64 */
            beforeSize?: number;
            changeType?: string;
            diffAvailable?: boolean;
            diffBinary?: boolean;
            diffTruncated?: boolean;
            diffWhitespaceOnly?: boolean;
            existed?: boolean;
            /** Format: int32 */
            linesAdded?: number;
            /** Format: int32 */
            linesDeleted?: number;
            parentThreadId?: string;
            path?: string;
            scope?: string;
            skipped?: boolean;
            skippedReason?: string;
            subagentThreadId?: string;
            threadId?: string;
        };
        Fork: {
            commandId?: string;
            leafId?: string;
            title?: string;
        };
        GoalBudget: {
            /** Format: int64 */
            activeWallClockBudgetMs: number;
            /** Format: double */
            costBudget: number;
            /** Format: int64 */
            elapsedDeadline?: number;
            /** Format: int32 */
            maxGoalTurns: number;
            /** Format: int32 */
            maxModelCalls: number;
            /** Format: int32 */
            maxToolCalls: number;
            /** Format: int64 */
            tokenBudget: number;
        };
        GoalBudgetRequest: {
            /** Format: int64 */
            activeWallClockBudgetMs?: number;
            /** Format: double */
            costBudget?: number;
            /** Format: int64 */
            elapsedDeadline?: number;
            /** Format: int32 */
            maxGoalTurns?: number;
            /** Format: int32 */
            maxModelCalls?: number;
            /** Format: int32 */
            maxToolCalls?: number;
            /** Format: int64 */
            tokenBudget?: number;
        };
        GoalCreateRequest: {
            budget?: components["schemas"]["GoalBudgetRequest"];
            clientRequestId?: string;
            conversationId?: string;
            criteria?: components["schemas"]["GoalCriterionRequest"][];
            /** @enum {string} */
            notificationMode?: "OFF" | "ALL" | "FAILURES";
            objective?: string;
            run?: components["schemas"]["RunConfig"];
            start?: boolean;
        };
        GoalCriterion: {
            /** @enum {string} */
            assertion?: "EXISTS" | "NOT_EXISTS" | "CONTAINS";
            criterionId: string;
            expected?: string;
            ref?: string;
            required: boolean;
            /** @enum {string} */
            type: "TEXT" | "VERIFY_CHECK" | "FILE_ASSERTION" | "TODO_COMPLETE" | "USER_REVIEW";
        };
        GoalCriterionRequest: {
            assertion?: string;
            criterionId?: string;
            expected?: string;
            ref?: string;
            required?: boolean;
            type?: string;
        };
        GoalExecutionView: {
            /** Format: int64 */
            activeWallClockMs?: number;
            /** Format: int32 */
            blockerCount?: number;
            completionRequest?: components["schemas"]["CompletionRequest"];
            /** Format: int32 */
            consecutiveNoProgress?: number;
            cost?: components["schemas"]["CostView"];
            criteriaResults?: components["schemas"]["CriterionResult"][];
            /** Format: int32 */
            modelCalls?: number;
            /** Format: int64 */
            retryAt?: number;
            /** Format: int32 */
            retryAttempts?: number;
            steps?: components["schemas"]["StepAttempt"][];
            stopReason?: string;
            /** Format: int32 */
            toolCalls?: number;
            /** Format: int32 */
            turnsUsed?: number;
        };
        GoalHistory: {
            goalId?: string;
            runs?: components["schemas"]["RunView"][];
        };
        GoalPage: {
            goals?: components["schemas"]["GoalView"][];
        };
        GoalPatchRequest: {
            budget?: components["schemas"]["GoalBudgetRequest"];
            clientRequestId?: string;
            criteria?: components["schemas"]["GoalCriterionRequest"][];
            objective?: string;
            /** Format: int64 */
            revision?: number;
            run?: components["schemas"]["RunConfig"];
        };
        GoalStartRunRequest: {
            clientRequestId?: string;
            /** @enum {string} */
            notificationMode?: "OFF" | "ALL" | "FAILURES";
            /** Format: int64 */
            revision?: number;
            run?: components["schemas"]["RunConfig"];
        };
        GoalView: {
            activeRunId?: string;
            /** Format: int64 */
            activeRunRevision?: number;
            budget: components["schemas"]["GoalBudget"];
            conversationId: string;
            /** Format: int64 */
            createdAt: number;
            criteria: components["schemas"]["GoalCriterion"][];
            goalId: string;
            instructionRevisions: components["schemas"]["InstructionRevision"][];
            latestRunId?: string;
            /** Format: int64 */
            latestRunRevision?: number;
            /** @enum {string} */
            latestRunStatus?: "CREATED" | "QUEUED" | "RUNNING" | "PAUSE_REQUESTED" | "PAUSED" | "WAITING_RETRY" | "READY_FOR_REVIEW" | "COMPLETED" | "INCOMPLETE" | "BLOCKED" | "BUDGET_LIMITED" | "FAILED" | "CANCELLED" | "INTERRUPTED" | "EXPIRED";
            objective: string;
            /** Format: int64 */
            revision: number;
            runDefaults: components["schemas"]["RunDefaults"];
            runIds: string[];
            /** Format: int32 */
            schemaVersion: number;
            /** @enum {string} */
            status: "CREATED" | "QUEUED" | "RUNNING" | "PAUSE_REQUESTED" | "PAUSED" | "WAITING_RETRY" | "READY_FOR_REVIEW" | "COMPLETED" | "INCOMPLETE" | "BLOCKED" | "BUDGET_LIMITED" | "FAILED" | "CANCELLED" | "INTERRUPTED" | "EXPIRED";
            /** Format: int64 */
            updatedAt: number;
        };
        Hit: {
            conversationId?: string;
            executionRecordId?: string;
            messageId?: string;
            runId?: string;
            snippet?: string;
            sourceKind?: string;
            /** Format: int64 */
            sourceRevision?: number;
            title?: string;
            toolCallId?: string;
        };
        ImageCapabilities: {
            backgrounds?: string[];
            edit?: boolean;
            formats?: string[];
            generate?: boolean;
            mask?: boolean;
            /** Format: int32 */
            maxCount?: number;
            /** Format: int64 */
            maxSourceBytes?: number;
            /** Format: int32 */
            maxSources?: number;
            previews?: boolean;
            qualities?: string[];
            query?: boolean;
            sizes?: string[];
            upstreamCancel?: boolean;
        };
        ImageOperationRecord: {
            /** @enum {string} */
            acceptance: "NOT_SENT" | "SENT_UNKNOWN" | "ACCEPTED" | "REJECTED" | "OUTPUT_RECEIVED" | "UNKNOWN";
            artifactIds: string[];
            configurationRef: string;
            conversationId: string;
            /** Format: int64 */
            createdAt: number;
            detail?: string;
            errorCode?: string;
            operationId: string;
            parentStepId?: string;
            parentToolCallId?: string;
            providerHandle?: string;
            requestHash: string;
            requestSnapshot: components["schemas"]["ImageSpec"];
            responseComplete: boolean;
            /** Format: int64 */
            revision: number;
            runId: string;
            /** Format: int32 */
            schemaVersion: number;
            scopeId: string;
            /** @enum {string} */
            state: "QUEUED" | "RUNNING" | "SAVING" | "SUCCEEDED" | "PARTIAL_SUCCESS" | "FAILED" | "CANCEL_REQUESTED" | "CANCELLED" | "UNKNOWN";
            /** Format: int64 */
            updatedAt: number;
            usage: {
                [key: string]: number;
            };
        };
        ImageSpec: {
            maskArtifactId?: string;
            parameters?: components["schemas"]["Parameters"];
            serviceId?: string;
            sourceArtifactIds?: string[];
        };
        ImageUpdate: {
            operation?: components["schemas"]["ImageOperationRecord"];
            operationId?: string;
            /** Format: int64 */
            revision?: number;
        };
        ImConnection: {
            allowMutations?: boolean;
            allowYolo?: boolean;
            cardTemplateId?: string;
            clientId?: string;
            clientSecret?: string;
            enabled?: boolean;
            peers?: components["schemas"]["ImPeer"][];
        };
        ImPairRequest: {
            revision?: string;
        };
        ImPeer: {
            chatId?: string;
            chatType?: string;
            label?: string;
            userId?: string;
        };
        ImSaveRequest: {
            connection?: components["schemas"]["ImConnection"];
            credentialId?: string;
            credentialRevision?: string;
            revision?: string;
        };
        ImSecretResponse: {
            clientSecret?: string;
        };
        InstructionRevision: {
            /** Format: int64 */
            appliedAt?: number;
            inputId?: string;
            /** Format: int64 */
            sequence?: number;
            text?: string;
        };
        InteractionUpdate: {
            kind?: string;
        };
        Limits: {
            /** Format: int32 */
            maxWorkIterations?: number;
        };
        Materialization: {
            /** Format: int64 */
            historyRevision?: number;
            parentMessageId?: string;
        };
        MessageCommit: {
            receipt?: components["schemas"]["CommitReceipt"];
        };
        MessagePage: {
            activeLeafId?: string | null;
            /** Format: int64 */
            historyRevision: number;
            items: components["schemas"]["MessageRecord"][];
            nextCursor?: string | null;
        };
        MessageRecord: {
            contentParts: components["schemas"]["ContentPart"][];
            /** Format: int64 */
            createdAt: number;
            messageId: string;
            parentMessageId?: string | null;
            revisesMessageId?: string | null;
            /** Format: int64 */
            revision: number;
            /** @enum {string} */
            role: "USER" | "ASSISTANT";
            runId: string | null;
            /** Format: int32 */
            schemaVersion: number;
            /** @enum {string} */
            status: "PENDING" | "STREAMING" | "COMPLETED" | "PROGRESS" | "INCOMPLETE" | "INTERRUPTED" | "FAILED" | "CANCELLED";
            turnId: string;
            /** Format: int64 */
            updatedAt: number;
        };
        ModelCallRecord: {
            attempts?: components["schemas"]["Attempt"][];
            callId?: string;
            conversationId?: string;
            /** Format: int64 */
            createdAt?: number;
            /** Format: int64 */
            finishedAt?: number;
            purpose?: string;
            requestedModel?: string;
            requestedProvider?: string;
            /** Format: int64 */
            revision?: number;
            runId?: string;
            /** Format: int32 */
            schemaVersion?: number;
            /** @enum {string} */
            state?: "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED" | "UNKNOWN";
            stepId?: string;
            usageComplete?: boolean;
        };
        ModelCallUpdate: {
            call?: components["schemas"]["ModelCallRecord"];
            callId?: string;
            /** Format: int64 */
            revision?: number;
        };
        NotificationRecipient: {
            account?: string;
            channel?: string;
            chatId?: string;
            chatType?: string;
            id?: string;
            label?: string;
            userId?: string;
        };
        NotificationRecipientOption: {
            available?: boolean;
            online?: boolean;
            reason?: string;
            recipient?: components["schemas"]["NotificationRecipient"];
        };
        NotificationSelection: {
            recipientId?: string;
            revision?: string;
        };
        NotificationSettingsView: {
            defaultRecipient?: components["schemas"]["NotificationRecipient"];
            recipients?: components["schemas"]["NotificationRecipientOption"][];
            revision?: string;
            valid?: boolean;
        };
        Parameters: {
            background?: string;
            /** Format: int32 */
            count?: number;
            format?: string;
            quality?: string;
            size?: string;
        };
        PendingInteraction: {
            callId: string;
            conversationId: string;
            /** Format: int64 */
            createdAt: number;
            kind: string;
            payload: {
                [key: string]: unknown;
            };
        };
        PendingSubmission: {
            clientRequestId: string;
            conversationId: string;
            /** Format: int64 */
            createdAt: number;
            materialization?: components["schemas"]["Materialization"];
            /** Format: int64 */
            ordinal: number;
            predecessorSubmissionId?: string;
            predecessorTurnId?: string;
            reason?: string;
            request: components["schemas"]["Request"];
            requestHash: string;
            /** Format: int64 */
            revision: number;
            runId?: string;
            /** Format: int32 */
            schemaVersion: number;
            scopeId: string;
            /** @enum {string} */
            state: "QUEUED" | "HELD" | "STARTING" | "STARTED" | "FAILED" | "CANCELLED";
            submissionId: string;
            turnId?: string;
            /** Format: int64 */
            updatedAt: number;
        };
        PendingSubmissionCreateRequest: {
            attachmentIds?: string[];
            channel?: string;
            clientRequestId: string;
            execution: components["schemas"]["AgentSpec"] | components["schemas"]["ConversationSpec"];
            input: string;
            /** @enum {string} */
            notificationMode?: "OFF" | "ALL" | "FAILURES";
            predecessorTurnId: string;
        };
        PendingSubmissionPage: {
            items: components["schemas"]["PendingSubmission"][];
        };
        PermissionContext: {
            allowMutations?: boolean;
        };
        Presentation: {
            cursor: components["schemas"]["PresentationCursor"];
            history: components["schemas"]["RunHistory"];
            inputs: components["schemas"]["RunInputView"][];
            /** Format: int64 */
            interactionRevision: number;
            interactions: components["schemas"]["PendingInteraction"][];
            live: components["schemas"]["Snapshot"];
            run: components["schemas"]["RunView"];
            /** Format: int32 */
            schemaVersion: number;
        };
        PresentationCursor: {
            /** Format: int64 */
            projectionSeq: number;
            replayAvailable: boolean;
            /** Format: int64 */
            streamEpoch?: number;
        };
        /** @description Choose exactly one source: project path, owned artifact, or inline HTML. */
        PreviewRequest: {
            artifactId?: string;
            html?: string;
            path?: string;
        };
        PreviewSession: {
            /** Format: int64 */
            expiresAt: number;
            id: string;
            mime: string;
            name: string;
            url: string;
        };
        ProcessMessage: {
            afterMessageId?: string;
            /** Format: int64 */
            seq?: number;
            text?: string;
            thinking?: string;
        };
        ProjectArtifactExportRequest: {
            clientRequestId?: string;
            expectedChecksum?: string;
            relativePath?: string;
        };
        ProjectArtifactExportResult: {
            artifactId?: string;
            /** Format: int64 */
            bytes?: number;
            checksum?: string;
            /** Format: int64 */
            exportedAt?: number;
            exportId?: string;
            relativePath?: string;
            scopeId?: string;
        };
        ProtocolInfo: {
            capabilities: string[];
            name: string;
            /** @enum {string} */
            runtimeProfile: "PERSONAL" | "PROJECT";
            runtimeSha256?: string;
            runtimeVersion: string;
            version: string;
        };
        ProviderAttempt: {
            /** Format: int32 */
            attempts?: number;
            circuitState?: string;
            model?: string;
            outcome?: string;
            provider?: string;
            reason?: string;
            subagentThreadId?: string;
        };
        ProviderTokenUsage: {
            /** Format: int64 */
            cachedInput?: number;
            /** Format: int64 */
            cacheWriteInput?: number;
            /** Format: int64 */
            completion?: number;
            /** Format: int64 */
            prompt?: number;
            /** Format: int64 */
            reasoning?: number;
        };
        RegisterFact: {
            key: string;
            leafId?: string | null;
            qualifier?: string | null;
            quote: string;
            sourceMessageId: string;
            /** Format: int64 */
            sourceRevision: number;
            supersedes?: string | null;
        };
        Request: {
            attachmentIds?: string[];
            channel?: string;
            configurationRef?: string;
            execution?: components["schemas"]["AgentSpec"] | components["schemas"]["ConversationSpec"];
            input?: string;
            /** @enum {string} */
            notificationMode?: "OFF" | "ALL" | "FAILURES";
            parentMessageId?: string;
            predecessorTurnId?: string;
            retryUserMessageId?: string;
        };
        ResourceRevision: {
            id?: string;
            /** Format: int64 */
            revision?: number;
        };
        RestoreFilePlan: {
            action?: string;
            beforeHash?: string;
            changeType?: string;
            conflict?: boolean;
            diffAvailable?: boolean;
            diffBinary?: boolean;
            diffTruncated?: boolean;
            diffWhitespaceOnly?: boolean;
            /** Format: int32 */
            linesAdded?: number;
            /** Format: int32 */
            linesDeleted?: number;
            path?: string;
            reason?: string;
            scope?: string;
        };
        Retry: {
            clientRequestId?: string;
            execution?: components["schemas"]["AgentSpec"] | components["schemas"]["ConversationSpec"];
            /** Format: int64 */
            expectedHistoryRevision?: number;
            rerunAgent?: boolean;
        };
        Review: {
            clientRequestId?: string;
            decision?: string;
            feedback?: string;
            /** Format: int64 */
            revision?: number;
        };
        Revision: {
            commandId?: string;
            contentParts?: components["schemas"]["ContentPart"][];
            /** Format: int64 */
            expectedHistoryRevision?: number;
        };
        RunConfig: {
            interactionTarget?: string;
            /** Format: int32 */
            maxWorkIterations?: number;
            model?: string;
            permissionMode?: string;
            /** @enum {string} */
            planReview?: "REQUIRED" | "AUTOMATIC";
            provider?: string;
            readOnly?: boolean;
            reasoningEffort?: string;
            toolsets?: string[];
        };
        RunCreateRequest: {
            attachmentIds?: string[];
            channel?: string;
            clientRequestId: string;
            conversationId?: string;
            execution: components["schemas"]["AgentSpec"] | components["schemas"]["ConversationSpec"];
            /** Format: int64 */
            expectedHistoryRevision?: number;
            input?: string;
            /** @enum {string} */
            notificationMode?: "OFF" | "ALL" | "FAILURES";
            parentMessageId?: string;
            stream?: boolean;
        };
        RunDefaults: {
            model?: string;
            options?: components["schemas"]["AgentExecutionOptions"];
            permissionMode?: string;
            provider?: string;
            reasoningEffort?: string;
            toolsets?: string[];
        };
        RunHistory: {
            answer?: components["schemas"]["MessageRecord"];
            conversation?: components["schemas"]["ConversationMeta"];
            turn?: components["schemas"]["TurnRecord"];
        };
        RunInputCreateRequest: {
            attachmentIds?: string[];
            channel?: string;
            clientRequestId?: string;
            mode?: string;
            text?: string;
        };
        RunInputPage: {
            inputs?: components["schemas"]["RunInputView"][];
        };
        RunInputResponse: {
            input?: components["schemas"]["RunInputView"];
        };
        RunInputView: {
            /** Format: int64 */
            appliedAfterSeq?: number;
            /** Format: int64 */
            appliedAt?: number;
            appliedStepId?: string;
            /** Format: int64 */
            applyingAt?: number;
            applyOperationId?: string;
            attachmentIds: string[];
            channel: string;
            clientRequestId: string;
            conversationId: string;
            /** Format: int64 */
            createdAt: number;
            inputId: string;
            /** @enum {string} */
            mode: "STEER";
            /** Format: int64 */
            revision: number;
            runId: string;
            /** Format: int32 */
            schemaVersion: number;
            /** Format: int64 */
            sequence: number;
            /** @enum {string} */
            status: "QUEUED" | "APPLYING" | "APPLIED" | "CANCELLED" | "HELD" | "SUPERSEDED" | "REJECTED";
            terminalReason?: string;
            text: string;
            /** Format: int64 */
            updatedAt?: number;
        };
        RunNotification: {
            /** @enum {string} */
            mode?: "OFF" | "ALL" | "FAILURES";
            recipient?: components["schemas"]["NotificationRecipient"];
        };
        RunOrigin: {
            /** @enum {string} */
            kind?: "CLIENT" | "GATEWAY" | "AUTOMATION";
            reference?: string;
        };
        RunPage: {
            runs?: components["schemas"]["RunView"][];
        };
        RunSubagentResult: {
            output: string;
        };
        RunToolDetail: {
            afterMessageId?: string;
            arguments?: string;
            content?: string;
            /** Format: int64 */
            durationMs?: number;
            id: string;
            metadata?: {
                [key: string]: unknown;
            };
            name: string;
            purpose?: string;
            resultPending: boolean;
            runId: string;
            /** Format: int64 */
            seq: number;
            state?: string;
            subagentConversationId?: string;
        };
        RunView: {
            activities: components["schemas"]["SemanticActivity"][];
            actualModel?: string;
            actualProvider?: string;
            allowedActions: string[];
            cancelRequested: boolean;
            channel?: string;
            controlState: string;
            conversationId: string;
            /** Format: int64 */
            createdAt: number;
            currentStepId?: string;
            error?: string;
            errorCode?: string;
            executing: boolean;
            executionConfig?: components["schemas"]["ExecutionConfig"];
            executionSpec: components["schemas"]["AgentSpec"] | components["schemas"]["ConversationSpec"];
            /** Format: int64 */
            finishedAt?: number;
            /** Format: int64 */
            firstAvailableSeq?: number;
            goalExecution?: components["schemas"]["GoalExecutionView"];
            goalId?: string;
            /** Format: int64 */
            lastSeq: number;
            notification?: components["schemas"]["RunNotification"];
            options?: components["schemas"]["AgentExecutionOptions"];
            origin: components["schemas"]["RunOrigin"];
            ownsConversation: boolean;
            /** Format: int32 */
            pendingInputCount: number;
            providerAttempts: components["schemas"]["ProviderAttempt"][];
            requestedModel?: string;
            requestedProvider?: string;
            requestedReasoningEffort?: string;
            result?: components["schemas"]["StepResult"];
            resultReceipt?: components["schemas"]["CommitReceipt"];
            /** Format: int64 */
            revision: number;
            runId: string;
            /** Format: int32 */
            schemaVersion: number;
            scopeId?: string;
            /** @enum {string} */
            status: "CREATED" | "QUEUED" | "RUNNING" | "PAUSE_REQUESTED" | "PAUSED" | "WAITING_RETRY" | "READY_FOR_REVIEW" | "COMPLETED" | "INCOMPLETE" | "BLOCKED" | "BUDGET_LIMITED" | "FAILED" | "CANCELLED" | "INTERRUPTED" | "EXPIRED";
            /** Format: int64 */
            streamEpoch?: number;
            subagents: components["schemas"]["SubagentState"][];
            terminal: boolean;
            turnId?: string;
            usage: components["schemas"]["Usage"];
        };
        SchedulePreview: {
            nextRuns?: number[];
            timeZone?: string;
        };
        ScheduleRequest: {
            cron?: string;
            timeZone?: string;
        };
        ScopeView: {
            instanceId: string;
            projectRoot?: string;
            runtimeDomainId: string;
            /** @enum {string} */
            runtimeProfile: "PERSONAL" | "PROJECT";
            scopeId: string;
        };
        SearchPage: {
            /** Format: int64 */
            indexedThrough?: number;
            items?: components["schemas"]["Hit"][];
            nextCursor?: string;
            /** @enum {string} */
            state?: "READY" | "CATCHING_UP" | "REBUILDING" | "UNAVAILABLE";
        };
        SemanticActivity: {
            activityKind: string;
            activityOutcome: string;
            activitySummary?: string;
            activityTargets?: string[];
            activityTitle: string;
            /** Format: int64 */
            afterTokens?: number;
            /** Format: int64 */
            beforeTokens?: number;
            callId?: string;
            childEventType?: string;
            delegateCallId?: string;
            eventType: string;
            id?: string;
            name?: string;
            outcome?: string;
            parentThreadId?: string;
            purpose?: string;
            /** Format: double */
            reductionPercent?: number;
            /** Format: int64 */
            seq: number;
            sizeEffective?: boolean;
            subagentThreadId?: string;
            summarySource?: string;
            toolName?: string;
            trigger?: string;
            /** Format: int64 */
            ts?: number;
        };
        ServiceView: {
            canSubmit?: boolean;
            capabilities?: components["schemas"]["ImageCapabilities"];
            code?: string;
            defaults?: components["schemas"]["Parameters"];
            label?: string;
            mainModel?: string;
            message?: string;
            model?: string;
            profile?: string;
            /** Format: int64 */
            retryAt?: number;
            serviceId?: string;
            /** @enum {string} */
            state?: "UNCONFIGURED" | "UNSUPPORTED" | "CONFIGURED_UNVERIFIED" | "AVAILABLE" | "TEMPORARILY_UNAVAILABLE";
        };
        Snapshot: {
            imageOperations: components["schemas"]["ResourceRevision"][];
            messages?: components["schemas"]["ProcessMessage"][];
            /** Format: int64 */
            messageStartSeq: number;
            modelCalls: components["schemas"]["ResourceRevision"][];
            text: string;
            thinking: string;
            tools: components["schemas"]["ToolActivity"][];
            truncated: boolean;
        };
        StepAttempt: {
            error?: string;
            /** Format: int64 */
            finishedAt?: number;
            /** Format: int32 */
            index?: number;
            /** @enum {string} */
            kind?: "GOAL_STEP";
            progressFingerprint?: string;
            /** Format: int64 */
            startedAt?: number;
            status?: string;
            stepId?: string;
        };
        StepLimits: {
            /** Format: int32 */
            finalizationGraceIterations?: number;
            /** Format: int32 */
            maxWorkIterations?: number;
        };
        StepResult: {
            detail?: string;
            errorCode?: string;
            output?: string;
            persisted?: boolean;
            /** @enum {string} */
            reason?: "RETURNED" | "STEP_LIMIT" | "RUN_BUDGET" | "NEEDS_INPUT" | "PAUSED" | "SUPERSEDED" | "CANCELLED" | "FAILED";
            runId?: string;
            stepId?: string;
            /** Format: int64 */
            totalTokens?: number;
        };
        StreamEvent: {
            conversationId?: string;
            data?: {
                [key: string]: Record<string, never>;
            };
            runId?: string;
            /** Format: int64 */
            seq?: number;
            /** Format: int64 */
            streamEpoch?: number;
            /** Format: int64 */
            ts?: number;
            type?: string;
        };
        SubagentState: {
            activitySummary?: string;
            activityTitle?: string;
            completionCriteria?: string;
            delegateCallId?: string;
            /** Format: int32 */
            depth?: number;
            details?: components["schemas"]["SemanticActivity"][];
            detailsTruncated?: boolean;
            error?: boolean;
            eventType?: string;
            /** Format: int64 */
            finishedAt?: number;
            /** Format: int64 */
            finishedSeq?: number;
            goal?: string;
            id?: string;
            /** Format: int32 */
            maxIterations?: number;
            model?: string;
            outcome: string;
            output?: string;
            parentThreadId?: string;
            /** Format: int64 */
            startedAt?: number;
            /** Format: int64 */
            startedSeq?: number;
            subagentThreadId?: string;
            terminalStatus?: string;
            toolsets?: string[];
        };
        SubmissionUpdate: {
            /** Format: int64 */
            revision?: number;
            state?: string;
            submissionId?: string;
        };
        SummaryPage: {
            items: components["schemas"]["ConversationMeta"][];
            nextCursor?: string;
        };
        TextDelta: {
            text?: string;
        };
        TextTarget: {
            model?: string;
            provider?: string;
            reasoningEffort?: string;
        };
        Title: {
            commandId?: string;
            /** Format: int64 */
            expectedMetadataRevision?: number;
            title?: string;
        };
        ToolActivity: {
            afterMessageId?: string;
            arguments?: string;
            content?: string;
            /** Format: int64 */
            durationMs?: number;
            id?: string;
            name?: string;
            purpose?: string;
            resultPending?: boolean;
            /** Format: int64 */
            seq?: number;
            state?: string;
            subagentConversationId?: string;
            summary?: string;
        };
        TurnRecord: {
            acceptedOperationId: string;
            assistantAttemptIds: string[];
            conversationId: string;
            executionSnapshot: components["schemas"]["AgentSpec"] | components["schemas"]["ConversationSpec"];
            predecessorTurnId?: string;
            requestHash?: string;
            requestId: string;
            runId: string;
            turnId: string;
            userMessageId: string;
        };
        Usage: {
            /** Format: int64 */
            cachedInput?: number;
            /** Format: int64 */
            cacheWriteInput?: number;
            complete?: boolean;
            /** Format: int64 */
            completion?: number;
            estimated?: boolean;
            /** Format: int32 */
            events?: number;
            /** Format: int32 */
            expectedEvents?: number;
            partial?: boolean;
            /** Format: int64 */
            prompt?: number;
            /** Format: int64 */
            reasoning?: number;
            /** Format: int32 */
            taskEvents?: number;
            /** Format: int64 */
            total?: number;
        };
        UsageDocument: {
            entries: components["schemas"]["UsageEntry"][];
            runId: string;
            /** Format: int32 */
            schemaVersion: number;
        };
        UsageEntry: {
            /** @enum {string} */
            acceptance: "NOT_SENT" | "SENT_UNKNOWN" | "ACCEPTED" | "REJECTED" | "OUTPUT_RECEIVED" | "UNKNOWN";
            attemptId: string;
            callId: string;
            conversationId?: string;
            costComplete: boolean;
            /** Format: int64 */
            finishedAt?: number;
            kind: string;
            knownAmount: number;
            model: string;
            operationId?: string;
            price?: components["schemas"]["UsagePrice"];
            provider: string;
            purpose: string;
            quantities: {
                [key: string]: number;
            };
            /** Format: int64 */
            revision: number;
            runId: string;
            /** Format: int64 */
            startedAt: number;
            /** @enum {string} */
            state: "RUNNING" | "SUCCEEDED" | "FAILED" | "CANCELLED" | "UNKNOWN";
            stepId?: string;
            usageComplete: boolean;
        };
        UsagePrice: {
            basis: string;
            currency?: string;
            rates: {
                [key: string]: number;
            };
            version?: string;
        };
        UsageReport: {
            entries: components["schemas"]["UsageEntry"][];
            summary: components["schemas"]["UsageSummary"];
        };
        UsageSummary: {
            /** Format: int32 */
            attempts: number;
            /** Format: int64 */
            calculatedAt: number;
            /** Format: int64 */
            completionTokens: number;
            costComplete: boolean;
            /** Format: int64 */
            images: number;
            knownAmounts: {
                [key: string]: number;
            };
            /** Format: int64 */
            promptTokens: number;
            runId: string;
            /** Format: int32 */
            unknownCostAttempts: number;
            /** Format: int32 */
            unknownUsageAttempts: number;
            usageComplete: boolean;
        };
        WorkspaceSnapshot: {
            agentId?: string;
            conversationHash?: string;
            conversationRestorable?: boolean;
            fileCoverage?: string;
            fileEntries?: components["schemas"]["FileSnapshotEntry"][];
            fileRestorable?: boolean;
            /** Format: int32 */
            iteration?: number;
            kind?: string;
            label?: string;
            /** Format: int32 */
            msgCount?: number;
            parentThreadId?: string;
            rootThreadId?: string;
            runId?: string;
            /** Format: int64 */
            seq: number;
            subagentThreadId?: string;
            takenAt?: string;
            threadId?: string;
            toolCallId?: string;
            toolSummaries?: string[];
            vcs?: Record<string, never>;
            warnings?: string[];
        };
        WorkspaceSnapshotBranchRequest: {
            commandId: string;
            title?: string;
        };
        WorkspaceSnapshotBranchResult: {
            conversationId: string;
        };
        WorkspaceSnapshotRestorePlan: {
            canRestoreFiles?: boolean;
            conflicts?: components["schemas"]["RestoreFilePlan"][];
            conversationRestorable?: boolean;
            fileCoverage?: string;
            files?: components["schemas"]["RestoreFilePlan"][];
            /** Format: int64 */
            historyRevision?: number;
            mode: string;
            /** Format: int64 */
            seq: number;
            warnings?: string[];
        };
        WorkspaceSnapshotRestorePlanRequest: {
            mode?: string;
            paths?: string[];
        };
        WorkspaceSnapshotRestoreRequest: {
            commandId: string;
            /** Format: int64 */
            expectedHistoryRevision?: number;
            force?: boolean;
            mode?: string;
            paths?: string[];
        };
        WorkspaceSnapshotRestoreResult: {
            conflicts?: components["schemas"]["RestoreFilePlan"][];
            conversationId?: string;
            conversationRestored?: boolean;
            failedFiles?: {
                [key: string]: unknown;
            }[];
            fileCoverage?: string;
            filesRestored?: boolean;
            /** Format: int64 */
            historyRevision?: number;
            leafId?: string;
            reason?: string;
            restored?: boolean;
            /** Format: int32 */
            restoredFileCount?: number;
            /** Format: int32 */
            skippedFileCount?: number;
            warnings?: string[];
        };
    };
    responses: never;
    parameters: never;
    requestBodies: never;
    headers: never;
    pathItems: never;
}
export type $defs = Record<string, never>;
export interface operations {
    cronList: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["CronJobView"][];
                };
            };
        };
    };
    cronAdd: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["CronJob"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": Record<string, never>;
                };
            };
        };
    };
    cronDel: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: Record<string, never>;
                    };
                };
            };
        };
    };
    cancelExecution: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: boolean;
                    };
                };
            };
        };
    };
    cronEnabled: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["EnabledRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: Record<string, never>;
                    };
                };
            };
        };
    };
    history_1: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["AutomationAttempt"][];
                };
            };
        };
    };
    cronRun: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": Record<string, never>;
                };
            };
        };
    };
    cronPreview: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ScheduleRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SchedulePreview"];
                };
            };
        };
    };
    result: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                attemptId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["AutomationExecutionResult"];
                };
            };
        };
    };
    targets: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["AutomationTargetView"][];
                };
            };
        };
    };
    notificationSettings: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                targetId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["NotificationSettingsView"];
                };
            };
        };
    };
    toolsets: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: string[];
                    };
                };
            };
        };
    };
    view: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: Record<string, never>;
                    };
                };
            };
        };
    };
    save: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                channel: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ImSaveRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: Record<string, never>;
                    };
                };
            };
        };
    };
    reconnect: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                channel: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: Record<string, never>;
                    };
                };
            };
        };
    };
    revealSecret: {
        parameters: {
            query: {
                clientId: string;
                revision: string;
            };
            header?: never;
            path: {
                channel: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ImSecretResponse"];
                };
            };
        };
    };
    revealSavedSecret: {
        parameters: {
            query: {
                revision: string;
            };
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ImSecretResponse"];
                };
            };
        };
    };
    template: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": string;
                };
            };
        };
    };
    notifications_1: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["NotificationSettingsView"];
                };
            };
        };
    };
    notifications: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["NotificationSelection"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["NotificationSettingsView"];
                };
            };
        };
    };
    pair: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ImPairRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: Record<string, never>;
                    };
                };
            };
        };
    };
    list_7: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["WorkspaceSnapshot"][];
                };
            };
        };
    };
    take: {
        parameters: {
            query?: {
                label?: string;
            };
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: Record<string, never>;
                    };
                };
            };
        };
    };
    branch_1: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
                seq: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["WorkspaceSnapshotBranchRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["WorkspaceSnapshotBranchResult"];
                };
            };
        };
    };
    restore: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
                seq: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["WorkspaceSnapshotRestoreRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["WorkspaceSnapshotRestoreResult"];
                };
            };
        };
    };
    restorePlan: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
                seq: number;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["WorkspaceSnapshotRestorePlanRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["WorkspaceSnapshotRestorePlan"];
                };
            };
        };
    };
    metadata: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ArtifactRecord"];
                };
            };
        };
    };
    content: {
        parameters: {
            query?: {
                download?: boolean;
            };
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": string;
                };
            };
        };
    };
    export_1: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Export"];
                };
            };
        };
    };
    export: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["ProjectArtifactExportRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ProjectArtifactExportResult"];
                };
            };
        };
    };
    upload: {
        parameters: {
            query?: {
                clientRequestId?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: {
            content: {
                "multipart/form-data": {
                    /** Format: binary */
                    file: string;
                };
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ArtifactRecord"];
                };
            };
        };
    };
    create_3: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["AutomationExecutionRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["AutomationExecutionStart"];
                };
            };
        };
    };
    get_7: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                attemptId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["AutomationExecutionResult"];
                };
            };
        };
    };
    cancel_2: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                attemptId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": {
                        [key: string]: boolean;
                    };
                };
            };
        };
    };
    list_5: {
        parameters: {
            query?: {
                cursor?: string;
                limit?: number;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SummaryPage"];
                };
            };
        };
    };
    list_6: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                conversationId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PendingSubmissionPage"];
                };
            };
        };
    };
    enqueue_1: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                conversationId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PendingSubmissionCreateRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PendingSubmission"];
                };
            };
        };
    };
    cancel_1: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                conversationId: string;
                submissionId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PendingSubmission"];
                };
            };
        };
    };
    get_6: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ConversationMeta"];
                };
            };
        };
    };
    delete: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["Delete"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["CommitReceipt"];
                };
            };
        };
    };
    branch: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["Branch"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["CommitReceipt"];
                };
            };
        };
    };
    executionRecord: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
                recordId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ExecutionSearchRecord"];
                };
            };
        };
    };
    facts: {
        parameters: {
            query?: {
                leafId?: string;
                limit?: number;
                offset?: number;
            };
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["FactPage"];
                };
            };
        };
    };
    registerFact: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["RegisterFact"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Fact"];
                };
            };
        };
    };
    fork: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["Fork"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["CommitReceipt"];
                };
            };
        };
    };
    messages: {
        parameters: {
            query?: {
                cursor?: string;
                leafId?: string;
                limit?: number;
            };
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["MessagePage"];
                };
            };
        };
    };
    message: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
                messageId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["MessageRecord"];
                };
            };
        };
    };
    retry: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
                messageId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["Retry"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": Record<string, never>;
                };
            };
        };
    };
    revise: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
                messageId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["Revision"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["CommitReceipt"];
                };
            };
        };
    };
    title: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["Title"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["CommitReceipt"];
                };
            };
        };
    };
    turn: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
                turnId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["TurnRecord"];
                };
            };
        };
    };
    search: {
        parameters: {
            query: {
                conversationId?: string;
                cursor?: string;
                limit?: number;
                q: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["SearchPage"];
                };
            };
        };
    };
    list_4: {
        parameters: {
            query?: {
                conversationId?: string;
                status?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["GoalPage"];
                };
            };
        };
    };
    create_2: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["GoalCreateRequest"];
            };
        };
        responses: {
            /** @description Created */
            201: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["GoalView"];
                };
            };
        };
    };
    get_5: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                goalId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["GoalView"];
                };
            };
        };
    };
    patch: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                goalId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["GoalPatchRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["GoalView"];
                };
            };
        };
    };
    history: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                goalId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["GoalHistory"];
                };
            };
        };
    };
    startRun: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                goalId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["GoalStartRunRequest"];
            };
        };
        responses: {
            /** @description Accepted */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["RunView"];
                };
            };
        };
    };
    operation: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ImageOperationRecord"];
                };
            };
        };
    };
    catalog: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Catalog"];
                };
            };
        };
    };
    create_1: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["PreviewRequest"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["PreviewSession"];
                };
            };
        };
    };
    remove: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                id: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content?: never;
            };
        };
    };
    protocol: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ProtocolInfo"];
                };
            };
        };
    };
    list: {
        parameters: {
            query?: {
                conversationId?: string;
                status?: string;
            };
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["RunPage"];
                };
            };
        };
    };
    create: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["RunCreateRequest"];
            };
        };
        responses: {
            /** @description Accepted */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RunView"];
                };
            };
        };
    };
    get: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                runId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": components["schemas"]["RunView"];
                };
            };
        };
    };
    cancel: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                runId: string;
            };
            cookie?: never;
        };
        requestBody?: {
            content: {
                "application/json": components["schemas"]["Control"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": Record<string, never>;
                };
            };
        };
    };
    list_3: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                runId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ContextPlan"][];
                };
            };
        };
    };
    get_4: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                planId: string;
                runId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ContextPlan"];
                };
            };
        };
    };
    events: {
        parameters: {
            query?: {
                afterSeq?: number;
                streamEpoch?: number;
            };
            header?: never;
            path: {
                runId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "application/json": Record<string, never>;
                    "text/event-stream": Record<string, never>;
                };
            };
        };
    };
    list_2: {
        parameters: {
            query?: {
                status?: string;
            };
            header?: never;
            path: {
                runId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["RunInputPage"];
                };
            };
        };
    };
    enqueue: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                runId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["RunInputCreateRequest"];
            };
        };
        responses: {
            /** @description Accepted */
            202: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["RunInputResponse"];
                };
            };
        };
    };
    withdraw: {
        parameters: {
            query?: {
                clientRequestId?: string;
            };
            header?: never;
            path: {
                inputId: string;
                runId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["RunInputResponse"];
                };
            };
        };
    };
    list_1: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                runId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ModelCallRecord"][];
                };
            };
        };
    };
    get_3: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                callId: string;
                runId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ModelCallRecord"];
                };
            };
        };
    };
    status: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["DiagnosticStatus"];
                };
            };
        };
    };
    pause: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                runId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["Control"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": Record<string, never>;
                };
            };
        };
    };
    get_2: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                runId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["Presentation"];
                };
            };
        };
    };
    resume: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                runId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["Control"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": Record<string, never>;
                };
            };
        };
    };
    review: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                runId: string;
            };
            cookie?: never;
        };
        requestBody: {
            content: {
                "application/json": components["schemas"]["Review"];
            };
        };
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": Record<string, never>;
                };
            };
        };
    };
    subagentResult: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                runId: string;
                threadId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["RunSubagentResult"];
                };
            };
        };
    };
    get_1: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                runId: string;
                seq: number;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["RunToolDetail"];
                };
            };
        };
    };
    report: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                runId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["UsageReport"];
                };
            };
        };
    };
    entries: {
        parameters: {
            query?: never;
            header?: never;
            path: {
                runId: string;
            };
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["UsageDocument"];
                };
            };
        };
    };
    scope: {
        parameters: {
            query?: never;
            header?: never;
            path?: never;
            cookie?: never;
        };
        requestBody?: never;
        responses: {
            /** @description OK */
            200: {
                headers: {
                    [name: string]: unknown;
                };
                content: {
                    "*/*": components["schemas"]["ScopeView"];
                };
            };
        };
    };
}
