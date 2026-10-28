#!/usr/bin/env python3
"""Generates infra/keycloak/oms-realm.json (the realm Keycloak imports on first start).
Edit this file, then:  python3 infra/keycloak/generate-realm.py infra/keycloak/oms-realm.json
Keycloak only imports a realm that does not exist yet; to apply changes locally the realm must be deleted
first (that deletes its users/sessions - ask before doing it) or changed through the admin console."""
import json, sys

AUD = "oms-api"
CUSTOMER_SESSION = {"client.session.idle.timeout": "1800", "client.session.max.lifespan": "36000"}   # 30 min sliding

roles = [
    ("CUSTOMER", "Shoppers: own cart, own orders, own returns, own profile"),
    ("SUPPORT_AGENT", "Call-centre agents: search all orders, view details, cancel unshipped orders, create returns, refunds up to $200, use chatbot"),
    ("SUPPORT_SUPERVISOR", "Team leads: everything an agent can + refunds above $200 + approve flagged returns"),
    ("OPS_ADMIN", "Operations team: integration screens, view and retry failed SAP / carrier messages, run SFTP jobs"),
    ("FINANCE", "Finance team: payment history, reconciliation reports, daily sales reports"),
    ("SERVICE", "Backend services: /internal/** APIs only"),
]

def audience_mapper():
    return {
        "name": "oms-api-audience",
        "protocol": "openid-connect",
        "protocolMapper": "oidc-audience-mapper",
        "consentRequired": False,
        "config": {
            "included.custom.audience": AUD,
            "id.token.claim": "false",
            "access.token.claim": "true",
            "introspection.token.claim": "true",
        },
    }

def public_client(cid, name, redirects, origins, extra_attrs=None):
    attrs = {"pkce.code.challenge.method": "S256", "post.logout.redirect.uris": "+"}
    attrs.update(extra_attrs or {})
    return {
        "clientId": cid, "name": name, "enabled": True, "publicClient": True,
        "protocol": "openid-connect", "standardFlowEnabled": True,
        "implicitFlowEnabled": False, "directAccessGrantsEnabled": False,
        "serviceAccountsEnabled": False, "fullScopeAllowed": True,
        "redirectUris": redirects, "webOrigins": origins,
        "attributes": attrs, "protocolMappers": [audience_mapper()],
    }

def service_client(cid):
    return {
        "clientId": cid, "name": cid, "enabled": True, "publicClient": False,
        "protocol": "openid-connect", "clientAuthenticatorType": "client-secret",
        # LOCAL DEV ONLY. In Kubernetes/AWS the secret comes from a Secret, never from Git.
        "secret": f"dev-{cid}-secret",
        "standardFlowEnabled": False, "implicitFlowEnabled": False, "directAccessGrantsEnabled": False,
        "serviceAccountsEnabled": True, "fullScopeAllowed": True,
        "attributes": {}, "protocolMappers": [audience_mapper()],
    }

clients = [
    public_client("mobile-app", "Mobile app (React Native)",
                  ["com.retail.oms.mobile://callback", "http://localhost:8081/*"], ["+"], CUSTOMER_SESSION),
    public_client("web-app", "Storefront web",
                  ["http://localhost:3000/*"], ["http://localhost:3000"], CUSTOMER_SESSION),
    public_client("admin-portal", "Angular admin portal",
                  ["http://localhost:4200/*"], ["http://localhost:4200"],
                  # agents: 8 hours sliding refresh = the realm default, nothing to override
                  ),
    {   # dev/test only: lets curl and scripts get a token with a username and password
        **public_client("oms-dev-cli", "DEV ONLY: password-grant client for curl and scripts", [], [], CUSTOMER_SESSION),
        "standardFlowEnabled": False, "directAccessGrantsEnabled": True,
    },
]
service_names = ["cart-service", "order-service", "pricing-service",
                 "order-orchestrator", "inventory-service", "payment-service"]
clients += [service_client(n) for n in service_names]

def user(username, first, last, roles=None, groups=None, sa_client=None):
    u = {"username": username, "enabled": True, "emailVerified": True,
         "email": f"{username}@example.com" if not sa_client else f"{username}@service.local",
         "firstName": first, "lastName": last}
    if sa_client:
        u["serviceAccountClientId"] = sa_client
    else:
        # LOCAL DEV ONLY test accounts
        u["credentials"] = [{"type": "password", "value": "Passw0rd!", "temporary": False}]
    if roles: u["realmRoles"] = roles
    if groups: u["groups"] = groups
    return u

users = [
    user("customer1", "Casey", "Customer", groups=["/customers"]),
    user("agent1", "Alex", "Agent", roles=["SUPPORT_AGENT"], groups=["/employees"]),
    user("supervisor1", "Sam", "Supervisor", roles=["SUPPORT_SUPERVISOR"], groups=["/employees"]),
    user("ops1", "Olive", "Ops", roles=["OPS_ADMIN"], groups=["/employees"]),
    user("finance1", "Finn", "Finance", roles=["FINANCE"], groups=["/employees"]),
] + [user(f"service-account-{n}", n, "service", roles=["SERVICE"], sa_client=n) for n in service_names]

realm = {
    "realm": "oms",
    "displayName": "Order Management Platform",
    "enabled": True,
    "registrationAllowed": True,          # customers self-register
    "registrationEmailAsUsername": False,
    "loginWithEmailAllowed": True,
    "resetPasswordAllowed": True,
    "verifyEmail": False,                 # enable once Mailpit is wired in
    "bruteForceProtected": True,
    "sslRequired": "external",
    "accessTokenLifespan": 300,           # 5 minutes
    # Realm = the longest allowed (agents: 8 hours sliding). Keycloak rejects a client limit above the realm limit,
    # so customer-facing clients get the shorter 30 minutes as a per-client override (CUSTOMER_SESSION below).
    "ssoSessionIdleTimeout": 28800,
    "ssoSessionMaxLifespan": 43200,
    "defaultSignatureAlgorithm": "RS256",
    "roles": {"realm": [
        {"name": n, "description": d, "composite": n == "SUPPORT_SUPERVISOR",
         **({"composites": {"realm": ["SUPPORT_AGENT"]}} if n == "SUPPORT_SUPERVISOR" else {})}
        for n, d in roles
    ]},
    "groups": [
        {"name": "customers", "path": "/customers", "realmRoles": ["CUSTOMER"]},
        {"name": "employees", "path": "/employees"},   # roles are assigned per user; federate LDAP/SAML here in real life
    ],
    "defaultGroups": ["/customers"],
    "clients": clients,
    "users": users,
}
json.dump(realm, open(sys.argv[1], "w"), indent=2)
print("clients:", [c["clientId"] for c in clients])
print("roles:", [r["name"] for r in realm["roles"]["realm"]])
