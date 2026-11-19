# Fat Free CRM [![Discord][discord-img-url]][discord-url]

[discord-img-url]: https://img.shields.io/badge/chat-on%20discord-7289da.svg?sanitize=true
[discord-url]: https://discord.gg/JVrzD8RYyk
### An open source, Ruby on Rails [customer relationship management][crm-wiki] platform (CRM).

[crm-wiki]: http://en.wikipedia.org/wiki/Customer_relationship_management

<img width="100%" alt="image" src="https://github.com/fatfreecrm/fatfreecrm.github.com/raw/master/images/dashboard.png" />


Out of the box it features group collaboration, campaign and lead management,
contact lists, and opportunity tracking.

<table>
  <tr>
    <td align="center" width="25%">
      <a href="https://github.com/fatfreecrm/fatfreecrm.github.com/raw/master/images/contact_create.png" target="_blank" title="Create Contacts">
        <img src="https://github.com/fatfreecrm/fatfreecrm.github.com/raw/master/images/contact_create.png" alt="Create Contacts">
      </a>
      <br />
      <em>Contacts</em>
    </td>
    <td align="center" width="25%">
      <a href="https://github.com/fatfreecrm/fatfreecrm.github.com/raw/master/images/contact_opportunity.png" target="_blank" title="Manage Opportunities">
        <img src="https://github.com/fatfreecrm/fatfreecrm.github.com/raw/master/images/contact_opportunity.png" alt="Manage Opportunities">
      </a>
      <br />
      <em>Opportunities</em>
    </td>
    <td align="center" width="25%">
      <a href="https://github.com/fatfreecrm/fatfreecrm.github.com/raw/master/images/account_edit.png" target="_blank" title="Edit Accounts">
        <img src="https://github.com/fatfreecrm/fatfreecrm.github.com/raw/master/images/account_edit.png" alt="Edit Accounts">
      </a>
      <br />
      <em>Accounts</em>
    </td>
    <td align="center" width="25%">
      <a href="https://github.com/fatfreecrm/fatfreecrm.github.com/raw/master/images/task_create.png" target="_blank" title="Create Tasks">
        <img src="https://github.com/fatfreecrm/fatfreecrm.github.com/raw/master/images/task_create.png" alt="Create Tasks">
      </a>
      <br />
      <em>Tasks</em>
    </td>
  </tr>
</table>

Build out your data with *custom fields*, organise with *tags*, model your organisation's _groups_ and _teams_.

Extend the core with [plugins for webhooks handling and sending, merging records, time tracking and more](https://github.com/fatfreecrm/fat_free_crm/wiki/Available-Plugins).

Join a [large community](https://github.com/fatfreecrm/fat_free_crm/wiki/People-Using-Fat-Free-CRM) building on open source solutions.


Pull requests and bug reports are always welcome!

Visit our website at http://www.fatfreecrm.com/ or check out the [documentation](https://www.rubydoc.info/github/fatfreecrm/fat_free_crm.git/master).


## System Requirements

* Ruby 3.4 (what CI and the `Dockerfile` use; the gemspec allows `>= 3.1`)
* Ruby on Rails 8.0 and the other gems, installed by Bundler
* PostgreSQL (the Gemfile default), MySQL or SQLite 3 (the Gemfile switches to these only when `CI` is set, e.g. `CI=1 DB=sqlite`)
* ImageMagick (optional, only needed if you would like to use avatars)


## Architecture

See [docs/architecture.md](docs/architecture.md) for how the application is put together today: processes, request path, domain model, background jobs and external integrations. The diagram ([PNG](docs/diagrams/architecture.png), [SVG](docs/diagrams/architecture.svg)) is generated from the editable [Graphviz source](docs/diagrams/architecture.dot) by `docs/diagrams/render.sh`.

[docs/migration/](docs/migration/) holds a proposal for a Spring Boot rewrite. It is not implemented.


## Installation

### Quick start (local development)

```bash
bundle install                                       # PostgreSQL; use CI=1 DB=sqlite (or DB=mysql) to swap the adapter gem
cp config/database.postgres.yml config/database.yml  # or database.sqlite.yml / database.mysql.yml
bundle exec rake db:create                           # ffcrm:setup only migrates; it doesn't create the database
bundle exec rake ffcrm:setup                         # creates the schema and asks for an admin user
bundle exec rake ffcrm:demo:load                     # optional demo data; replaces all users, including your admin
bundle exec rails server                             # http://localhost:3000
bin/jobs                                             # in a second terminal: Solid Queue worker for email and account-enrichment jobs
```

If you use `CI=1 DB=...`, export it in every terminal, including the one running `bin/jobs`. `ffcrm:setup` asks for confirmation before it migrates. `ffcrm:demo:load` loads `db/demo/users.yml` as fixtures, which replaces the admin user created by `ffcrm:setup`. Run `ffcrm:setup:admin` again afterwards to get your login back. To create the admin user without being asked, run `bundle exec rake ffcrm:setup:admin USERNAME=... PASSWORD=... EMAIL=...`.

### Docker

`docker compose up` builds the app image and runs it in production mode on port 3000 with a `postgres:16` service. The `postgres:16` image won't start unless `POSTGRES_PASSWORD` (with a matching `DB_PASSWORD`) or `POSTGRES_HOST_AUTH_METHOD=trust` is set on the `db` service, and `docker-compose.yml` doesn't set either. The first time, run `docker compose exec web bundle exec rake db:create ffcrm:setup` to create the database, schema and admin user. The compose file doesn't start a job worker.

### Setup guides

These guides are older and may still describe earlier Ruby and Rails versions:

* [Setup Linux or Mac OS](https://github.com/fatfreecrm/fat_free_crm/wiki/Setup-Linux-or-Mac-OS)
* [Setup Heroku](https://github.com/fatfreecrm/fat_free_crm/wiki/Setup-Heroku)
* [Setup Microsoft Windows](https://github.com/fatfreecrm/fat_free_crm/wiki/Setup-Microsoft-Windows) (deprecated)
* [Running Fat Free CRM as a Rails Engine](https://github.com/fatfreecrm/fat_free_crm/wiki/Running-as-a-Rails-Engine): run the Fat Free CRM gem inside a separate Rails application. This is the best way to deploy Fat Free CRM if you need to add plugins or make customizations.


## Upgrading from previous versions of Fat Free CRM

Please read the [Release notes](https://github.com/fatfreecrm/fat_free_crm/releases) or [Changelog](https://github.com/fatfreecrm/fat_free_crm/blob/master/CHANGELOG.md) document for more detailed information on upgrading from previous versions.


## Resources

|||
|-----------------------------------:|:--------------------------|
|                 **Home Page**: | http://www.fatfreecrm.com |
|                    **Guides**: | https://github.com/fatfreecrm/fat_free_crm/wiki |
|       **Github Project Page**: | http://github.com/fatfreecrm/fat_free_crm |
| **Feature Requests and Bugs**: | http://github.com/fatfreecrm/fat_free_crm/issues |
|                  **Ruby gem**: | https://rubygems.org/gems/fat_free_crm |
|       **User's Google Group**: | http://groups.google.com/group/fat-free-crm-users |
|  **Developer's Google Group**: | http://groups.google.com/group/fat-free-crm-dev |

## For Developers

Fat Free CRM is released under the MIT license and is freely available for you to use for your own purposes. We do encourage contributions to make Fat Free CRM even better. Send us a pull-request and we'll do our best to accommodate your needs.

Specific features that are not 'Fat Free' in nature, can be added by creating Rails Engines. See the [wiki](http://github.com/fatfreecrm/fat_free_crm/wiki) for information on how to do this.

Tests are RSpec specs in `spec/`. `bundle exec rake` runs the whole suite, which takes a while. CI (`.github/workflows/ruby.yml`) runs it like this:

```bash
cp config/database.sqlite.yml config/database.yml
export CI=1 DB=sqlite
bundle install
bundle exec rake spec:preparedb
bundle exec rake spec:models spec:controllers spec:views spec:features   # also spec:helpers, spec:routing, spec:mailers, spec:lib
bundle exec rubocop
```


## Main contributors

* [Michael Dvorkin (@michaeldv)](https://github.com/michaeldv) - Founding creator
* CloCkWeRX
* johnnyshield
* DmitryAvramec
* steveyken


See the [contributors graph](https://github.com/fatfreecrm/fat_free_crm/graphs/contributors) and the [contributors file](https://github.com/fatfreecrm/fat_free_crm/blob/master/CONTRIBUTORS.md) for further details.

## License

Fat Free CRM
Copyright (c) 2008-2018 Michael Dvorkin and contributors.

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject to
the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
