import {Component, inject} from '@angular/core';
import {bootstrapApplication} from '@angular/platform-browser';
import {provideAnimations} from '@angular/platform-browser/animations';
import {provideHttpClient} from '@angular/common/http';
import {MainComponent} from './app/main.component';
import {provideApollo} from "apollo-angular";
import {HttpLink} from "apollo-angular/http";
import {setContext} from "@apollo/client/link/context";
import {ApolloLink, InMemoryCache} from "@apollo/client/core";
import {environment} from "./environments/environment";

@Component({
  selector: 'app-root',
  template: `<app-main></app-main>`,
  standalone: true,
  imports: [MainComponent]
})
export class App {}

bootstrapApplication(App, {
  providers: [
    provideAnimations(),
    provideHttpClient(),
    provideApollo(() => {
      const httpLink = inject(HttpLink);

      const basic = setContext((operation, context) => ({
        headers: {
          Accept: 'application/json; charset=UTF-8',
        },
      }));

      const auth = setContext((operation, context) => {
        const token = localStorage.getItem('token');

        if (token === null) {
          return {};
        } else {
          return {
            headers: {
              Authorization: `Bearer ${token}`,
            },
          };
        }
      });

      return {
        link: ApolloLink.from([basic, auth, httpLink.create({ uri: environment.graphQlURL })]),
        cache: new InMemoryCache(),
        // other options ...
      };
    })
  ]
});