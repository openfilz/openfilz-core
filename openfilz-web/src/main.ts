import { Component, inject, provideAppInitializer } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { provideAnimations } from '@angular/platform-browser/animations';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { MainComponent } from './app/main.component';
import { provideApollo } from "apollo-angular";
import { HttpLink } from "apollo-angular/http";
import { setContext } from "@apollo/client/link/context";
import { ApolloLink, InMemoryCache } from "@apollo/client/core";
import { environment } from "./environments/environment";
import { provideRouter } from '@angular/router';
import { routes } from './app/app.routes';
import { provideAuth, LogLevel, authInterceptor, OidcSecurityService } from 'angular-auth-oidc-client';
import { MockAuthService } from './app/services/mock-auth.service';

@Component({
  selector: 'app-root',
  template: `<app-main></app-main>`,
  standalone: true,
  imports: [MainComponent]
})
export class App { }

bootstrapApplication(App, {
  providers: [
    provideAnimations(),
    provideHttpClient(
      environment.authentication.enabled
        ? withInterceptors([authInterceptor()])
        : withInterceptors([])
    ),
    provideRouter(routes),
    ...(environment.authentication.enabled ? [
      provideAuth({
        config: {
          authority: environment.authentication.authority,
          redirectUrl: window.location.origin,
          postLogoutRedirectUri: window.location.origin,
          clientId: environment.authentication.clientId,
          scope: 'openid profile email',
          responseType: 'code',
          silentRenew: true,
          useRefreshToken: true,
          logLevel: LogLevel.Debug,
          secureRoutes: [environment.apiURL, environment.graphQlURL],
        },
      }),
      provideAppInitializer(() => {
        const oidcSecurityService = inject(OidcSecurityService);
        return oidcSecurityService.checkAuth();
      })
    ] : [
      { provide: OidcSecurityService, useClass: MockAuthService }
    ]),
    provideApollo(() => {
      const httpLink = inject(HttpLink);

      const basic = setContext((operation, context) => ({
        headers: {
          Accept: 'application/json; charset=UTF-8',
        },
      }));

      return {
        link: ApolloLink.from([basic, httpLink.create({ uri: environment.graphQlURL })]),
        cache: new InMemoryCache(),
      };
    })
  ]
});