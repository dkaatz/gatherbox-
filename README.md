# Gatherbox

Gatherbox is a distributed scanner wich aggregates informations  
about Servers, Networks and Employees of companies by keywords.    

### Requirements: 
- SBT installed  (~0.13) 
- scala (~2.11)
- bunch of working proxy servers 

### Preparing Chrome Remote Webdrivers (Headless)
####1. Install docker 
##### - OSX:
```bash 
brew install docker
```
##### - Linux:
```bash 
apt-get install docker
```

####2. clone docker image
```bash
git clone git@github.com:SeleniumHQ/docker-selenium.git
```

####3. Start Docker Container
```bash
docker run -d -p 4444:4444 selenium/standalone-chrome
```