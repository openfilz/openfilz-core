import { Component, inject, provideAppInitializer, provideZoneChangeDetection } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';
import { provideAnimations } from '@angular/platform-browser/animations';
import { HttpHeaders, provideHttpClient, withInterceptors } from '@angular/common/http';
import { MainComponent } from './app/main.component';
import { provideApollo } from "apollo-angular";
import { HttpLink } from "apollo-angular/http";
import { SetContextLink } from "@apollo/client/link/context";
import { ApolloLink, InMemoryCache } from "@apollo/client/core";
import { environment } from "./environments/environment";
import { provideRouter } from '@angular/router';
import { routes } from './app/app.routes';
import { provideAuth, LogLevel, authInterceptor, OidcSecurityService } from 'angular-auth-oidc-client';
import { MockAuthService } from './app/services/mock-auth.service';
import { RoleService } from './app/services/role.service';
import { provideTranslateService } from '@ngx-translate/core';
import { provideTranslateHttpLoader } from '@ngx-translate/http-loader';
import { providePaginatorIntl } from './app/i18n/paginator-intl';

@Component({
  selector: 'app-root',
  template: `<app-main></app-main>`,
  standalone: true,
  imports: [MainComponent]
})
export class App { }

bootstrapApplication(App, {
  providers: [
    provideZoneChangeDetection(),provideAnimations(),
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
      provideAppInitializer(async () => {
        const oidcSecurityService = inject(OidcSecurityService);
        const roleService = inject(RoleService);

        const result = await oidcSecurityService.checkAuth().toPromise();

        if (result?.isAuthenticated) {
          const hasValidRoles = await roleService.initializeRoles();
          if (!hasValidRoles) {
            roleService.handleNoRoles();
          }
        }

        return result;
      })
    ] : [
      { provide: OidcSecurityService, useClass: MockAuthService }
    ]),
    provideApollo(() => {
      const httpLink = inject(HttpLink);
      const oidcSecurityService = inject(OidcSecurityService);

      const auth = new SetContextLink((_prevContext) => {
        const token = oidcSecurityService.getAccessToken();
        let headers = new HttpHeaders().set('Accept', 'application/json; charset=UTF-8');
        if (environment.authentication.enabled && token) {
          headers = headers.set('Authorization', `Bearer ${token}`);
        }
        return { headers };
      });

      return {
        link: ApolloLink.from([auth, httpLink.create({ uri: environment.graphQlURL })]),
        cache: new InMemoryCache()
      };
    }),
    provideTranslateService({
      defaultLanguage: 'en',
      loader: provideTranslateHttpLoader({
        prefix: './i18n/',
        suffix: '.json'
      })
    }),
    providePaginatorIntl()
  ]
});